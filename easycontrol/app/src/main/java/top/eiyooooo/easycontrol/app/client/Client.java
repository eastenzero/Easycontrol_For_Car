package top.eiyooooo.easycontrol.app.client;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.content.ClipData;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.EventMonitor;
import top.eiyooooo.easycontrol.app.helper.L;
import top.eiyooooo.easycontrol.app.helper.PublicTools;
import top.eiyooooo.easycontrol.app.BuildConfig;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.adb.Adb;
import top.eiyooooo.easycontrol.app.buffer.BufferStream;
import top.eiyooooo.easycontrol.app.client.view.ClientView;

public class Client {
  // 状态，0为初始，1为连接，-1为关闭
  private int status = 0;
  public static final ArrayList<Client> allClient = new ArrayList<>();

  // 连接
  public Adb adb;
  private BufferStream bufferStream;
  private BufferStream videoStream;
  private BufferStream audioStream;
  private BufferStream shell;

  // 子服务
  private final Thread executeStreamInThread = new Thread(this::executeStreamIn);
  private final Thread executeStreamVideoThread = new Thread(this::executeStreamVideo);
  private final Thread executeStreamAudioThread = new Thread(this::executeStreamAudio);
  private HandlerThread handlerThread;
  private Handler handler;
  private VideoDecode videoDecode;
  private AudioDecode audioDecode;
  public final ControlPacket controlPacket = new ControlPacket(this::write);
  public final ClientView clientView;
  public final String uuid;
  public int mode = 0; // 0为屏幕镜像模式，1为应用流转模式
  public int displayId = 0;
  private Thread startThread;
  private final Thread loadingTimeOutThread;
  private final Thread keepAliveThread;
  private static final int timeoutDelay = 5 * 1000;
  private long lastKeepAliveTime;
  public int multiLink = 0; // 0为单连接，1为多连接主，2为多连接从

  private boolean specifiedTransferred;

  private static final Pattern firstIntPattern = Pattern.compile("(-?\\d+)");

  private Integer sourceMusicVolume;
  private boolean sourceVolumeChanged;

  private static final String serverName = "/data/local/tmp/scrcpy-server-v3.1.jar";
  private static final String serverAssetName = "scrcpy-server-v3.1.jar";
  private static final String scrcpyVersion = "3.1";
  private static final boolean supportH265 = PublicTools.isDecoderSupport("hevc");
  private static final boolean supportOpus = PublicTools.isDecoderSupport("opus");

  public static boolean isAudioOnly = false;

  public Client(Device device, UsbDevice usbDevice, int mode) {
    for (Client client : allClient) {
      if (client.uuid.equals(device.uuid)) {
        if (client.multiLink == 0) client.changeMultiLinkMode(1);
        this.multiLink = 2;
        break;
      }
    }
    allClient.add(this);
    if (!EventMonitor.monitorRunning && AppData.setting.getMonitorState()) EventMonitor.startMonitor();
    // 初始化
    uuid = device.uuid;
    if (mode == 0) specifiedTransferred = true;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      handlerThread = new HandlerThread("easycontrol_mediacodec");
      handlerThread.start();
      handler = new Handler(handlerThread.getLooper());
    }
    clientView = new ClientView(device, controlPacket, this::changeMode, () -> {
      status = 1;
      executeStreamInThread.start();
      if (!isAudioOnly) executeStreamVideoThread.start();
      if (device.isAudio || isAudioOnly) executeStreamAudioThread.start();
      AppData.uiHandler.post(this::executeOtherService);
    }, () -> release(null));
    Pair<View, WindowManager.LayoutParams> loading = PublicTools.createLoading(AppData.main);
    // 连接
    loadingTimeOutThread = new Thread(() -> {
      try {
        Thread.sleep(timeoutDelay);
        if (startThread != null) startThread.interrupt();
        if (loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
        release(null);
      } catch (InterruptedException ignored) {
      }
    });
    keepAliveThread = new Thread(() -> {
      lastKeepAliveTime = System.currentTimeMillis();
      while (status != -1) {
        if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay)
          release(AppData.main.getString(R.string.error_stream_closed));
        try {
          Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
      }
    });
    startThread = new Thread(() -> {
      try {
        adb = connectADB(device, usbDevice);
        enableTcpNoDelay(adb);
        changeMode(mode);
        changeMultiLinkMode(multiLink);
        startServer(device);
        connectServer();
        if (isAudioOnly) initControlScreenSizeFromDevice(device);
        AppData.uiHandler.post(() -> {
          if (device.nightModeSync) controlPacket.sendNightModeEvent(AppData.nightMode);
          if (AppData.setting.getAlwaysFullMode() || device.defaultFull) clientView.changeToFull();
          else clientView.changeToSmall();
        });
      } catch (Exception e) {
        L.log(device.uuid, e);
        release(AppData.main.getString(R.string.log_notify));
      } finally {
        if (!AppData.setting.getAlwaysFullMode() && loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
        loadingTimeOutThread.interrupt();
        keepAliveThread.start();
      }
    });
    if (AppData.setting.getAlwaysFullMode()) PublicTools.logToast(AppData.main.getString(R.string.loading_text));
    else AppData.windowManager.addView(loading.first, loading.second);
    loadingTimeOutThread.start();
    startThread.start();
  }

  // 连接ADB
  private static Adb connectADB(Device device, UsbDevice usbDevice) throws Exception {
    if (Adb.adbMap.containsKey(device.uuid)) return Adb.adbMap.get(device.uuid);
    Adb adb;
    if (usbDevice == null) adb = new Adb(device.uuid, device.address, AppData.keyPair);
    else adb = new Adb(device.uuid, usbDevice, AppData.keyPair);
    Adb.adbMap.put(device.uuid, adb);
    return adb;
  }

  private static void enableTcpNoDelay(Adb adb) {
    if (adb == null) return;
    try {
      Field channelField = Adb.class.getDeclaredField("channel");
      channelField.setAccessible(true);
      Object channel = channelField.get(adb);
      if (channel == null) return;

      Field socketField = channel.getClass().getDeclaredField("socket");
      socketField.setAccessible(true);
      Object socket = socketField.get(channel);
      if (socket instanceof Socket) {
        ((Socket) socket).setTcpNoDelay(true);
      }
    } catch (Exception ignored) {
    }
  }

  // 启动Server
  private void startServer(Device device) throws Exception {
    if (adb.serverShell == null || adb.serverShell.isClosed()) adb.startServer();
    shell = adb.getShell();

    String resolvedServerName = serverName;
    String resolvedServerAssetName = serverAssetName;
    String resolvedScrcpyVersion = scrcpyVersion;
    String[] serverAssetCandidates = new String[]{serverAssetName, "scrcpy-server-v3.1", "scrcpy-server-v3.3.4", "scrcpy-server-v3.3.4.jar"};
    String[] serverRemoteCandidates = new String[]{serverName, "/data/local/tmp/scrcpy-server-v3.1.jar", "/data/local/tmp/scrcpy-server-v3.3.4.jar", "/data/local/tmp/scrcpy-server-v3.3.4.jar"};
    String[] serverVersionCandidates = new String[]{scrcpyVersion, "3.1", "3.3.4", "3.3.4"};
    for (int i = 0; i < serverAssetCandidates.length; i++) {
      try (InputStream assetProbe = AppData.main.getAssets().open(serverAssetCandidates[i])) {
        resolvedServerAssetName = serverAssetCandidates[i];
        resolvedServerName = serverRemoteCandidates[i];
        resolvedScrcpyVersion = serverVersionCandidates[i];
        break;
      } catch (Exception e) {
      }
    }

    try {
      String msg = "scrcpy-server asset=" + resolvedServerAssetName + ", remote=" + resolvedServerName + ", version=" + resolvedScrcpyVersion;
      Log.d("Client", msg);
      L.logWithoutTime(uuid, msg);
    } catch (Exception ignored) {
    }

    Log.d("Client", "Pushing server to device...");
    L.logWithoutTime(uuid, "Pushing server to device...");
    try (InputStream in = AppData.main.getAssets().open(resolvedServerAssetName)) {
      adb.pushFile(in, resolvedServerName);
    }
    Log.d("Client", "Push finished.");
    L.logWithoutTime(uuid, "Push finished.");

    try {
      String sizeAndLs = adb.runAdbCmd("sh -c '(stat -c %s " + resolvedServerName + " 2>/dev/null || wc -c < " + resolvedServerName + "); ls -l " + resolvedServerName + "'");
      if (sizeAndLs != null && !sizeAndLs.isEmpty()) {
        L.logWithoutTime(uuid, sizeAndLs);
        Integer size = parseFirstInt(sizeAndLs);
        if (size != null && size < 20000) {
          L.logWithoutTime(uuid, "scrcpy-server jar too small: " + size);
        }
      }
    } catch (Exception ignored) {
    }

    tryMuteSourceDevice();

    String videoCodec = "h264";
    String audioCodec = (device.useOpus && supportOpus) ? "opus" : "aac";
    int videoBitRate = device.maxVideoBit * 1000000;

    StringBuilder cmd = new StringBuilder();
    cmd.append("CLASSPATH=").append(resolvedServerName).append(" app_process / com.genymobile.scrcpy.Server ");
    cmd.append(resolvedScrcpyVersion);
    cmd.append(" tunnel_forward=true");
    cmd.append(" send_device_meta=false");
    cmd.append(" send_frame_meta=true");
    cmd.append(" send_dummy_byte=false");
    cmd.append(" send_codec_meta=true");
    cmd.append(" video=").append(!isAudioOnly);
    cmd.append(" audio=").append(isAudioOnly || device.isAudio);
    if (resolvedScrcpyVersion.startsWith("3.")) cmd.append(" audio_dup=false");
    cmd.append(" control=true");
    cmd.append(" show_touches=true");
    if (device.maxSize != 1600) cmd.append(" max_size=").append(device.maxSize);
    if (device.maxFps != 60) cmd.append(" max_fps=").append(device.maxFps);
    if (device.maxVideoBit != 4) cmd.append(" video_bit_rate=").append(videoBitRate);
    cmd.append(" video_codec=").append(videoCodec);
    cmd.append(" audio_codec=").append(audioCodec);
    if (displayId != 0) cmd.append(" display_id=").append(displayId);
    cmd.append(" stay_awake=").append(AppData.setting.getKeepAwake());
    cmd.append(" power_on=").append(AppData.setting.getTurnOnScreenIfStart());
    cmd.append(" power_off_on_close=").append(AppData.setting.getTurnOffScreenIfStop());
    cmd.append(" clipboard_autosync=").append(clientView.device.clipboardSync);
    cmd.append(" 2>&1\n");
    shell.write(ByteBuffer.wrap(cmd.toString().getBytes()));
    logger();
  }

  private void tryCreateDisplay(Device device) {
    if (displayId != 0) return;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
    try {
      String output = Adb.getStringResponseFromServer(device, "createVirtualDisplay");
      Matcher matcher = firstIntPattern.matcher(output);
      if (matcher.find()) {
        displayId = Integer.parseInt(matcher.group(1));
        clientView.displayId = displayId;
      }
    } catch (Exception ignored) {
    }
  }

  private void appTransfer(Device device) {
    if (specifiedTransferred) return;
    if (displayId == 0) return;
    String specifiedApp = device.specified_app;
    if (specifiedApp == null || specifiedApp.isEmpty()) {
      specifiedTransferred = true;
      return;
    }
    String packageName = specifiedApp;
    if (specifiedApp.contains("@")) {
      String[] parts = specifiedApp.split("@");
      if (parts.length > 0) packageName = parts[parts.length - 1];
    }
    if (packageName == null || packageName.isEmpty()) {
      specifiedTransferred = true;
      return;
    }
    try {
      String output = Adb.getStringResponseFromServer(device, "openAppByPackage", "package=" + packageName, "displayId=" + displayId);
      if (output != null && output.contains("success")) specifiedTransferred = true;
    } catch (Exception ignored) {
    }
  }

  private Integer parseFirstInt(String text) {
    if (text == null) return null;
    Matcher matcher = firstIntPattern.matcher(text);
    if (!matcher.find()) return null;
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (Exception ignored) {
      return null;
    }
  }

  private void tryMuteSourceDevice() {
    if (!(clientView.device.isAudio || isAudioOnly)) return;
    try {
      String out = adb.runAdbCmd("cmd media_session volume --get --stream 3");
      Integer vol = parseFirstInt(out);
      if (vol != null) sourceMusicVolume = vol;
    } catch (Exception ignored) {
    }
    String[] cmds = new String[]{
            "cmd media_session volume --set 0 --stream 3",
            "media volume --set 0 --stream 3"
    };
    for (String c : cmds) {
      try {
        adb.runAdbCmd(c);
        sourceVolumeChanged = true;
      } catch (Exception ignored) {
      }
    }
  }

  private void tryRestoreSourceDeviceVolume() {
    if (!sourceVolumeChanged || sourceMusicVolume == null) return;
    String[] cmds = new String[]{
            "cmd media_session volume --set " + sourceMusicVolume + " --stream 3",
            "media volume --set " + sourceMusicVolume + " --stream 3"
    };
    for (String c : cmds) {
      try {
        adb.runAdbCmd(c);
      } catch (Exception ignored) {
      }
    }
  }

  private Thread loggerThread;

  private void logger() {
    loggerThread = new Thread(() -> {
      try {
        while (!Thread.interrupted()) {
          String log = new String(shell.readAllBytes().array(), StandardCharsets.UTF_8);
          if (!log.isEmpty()) L.logWithoutTime(uuid, log);
          Thread.sleep(1000);
        }
      } catch (Exception ignored) {
      }
    });
    loggerThread.start();
  }

  // 连接Server
  private void connectServer() throws Exception {
    Thread.sleep(50);
    for (int i = 0; i < 60; i++) {
      try {
        String socketName = "scrcpy";
        if (!isAudioOnly) videoStream = adb.localSocketForward(socketName);
        if (clientView.device.isAudio || isAudioOnly) audioStream = adb.localSocketForward(socketName);
        bufferStream = adb.localSocketForward(socketName);
        return;
      } catch (Exception ignored) {
        Thread.sleep(50);
      }
    }
    throw new Exception(AppData.main.getString(R.string.error_connect_server));
  }

  private void initControlScreenSizeFromDevice(Device device) {
    try {
      String out = Adb.getStringResponseFromServer(device, "getDisplayInfo");
      if (out == null || out.isEmpty()) return;
      JSONArray arr = new JSONArray(out);
      if (arr.length() <= 0) return;

      JSONObject target = null;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        int id = obj.optInt("id", 0);
        if (id == displayId) {
          target = obj;
          break;
        }
      }
      if (target == null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject obj = arr.getJSONObject(i);
          int id = obj.optInt("id", 0);
          if (id == 0) {
            target = obj;
            break;
          }
        }
      }
      if (target == null) target = arr.getJSONObject(0);

      int w = target.optInt("width", 0);
      int h = target.optInt("height", 0);
      if (w > 0 && h > 0) controlPacket.setScreenSize(w, h);
    } catch (Exception ignored) {
    }
  }

  // 服务分发
  private static final int AUDIO_EVENT = 2;
  private static final int CLIPBOARD_EVENT = 3;
  private static final int CHANGE_SIZE_EVENT = 4;
  private static final int KEEP_ALIVE_EVENT = 5;

  private void executeStreamVideo() {
    try {
      while (status != -1 && videoStream == null) Thread.sleep(50);
      if (status == -1 || videoStream == null) return;

      int codecId = videoStream.readInt();
      int width = videoStream.readInt();
      int height = videoStream.readInt();
      Pair<Integer, Integer> videoSize = new Pair<>(width, height);
      controlPacket.setScreenSize(width, height);
      AppData.uiHandler.post(() -> clientView.updateVideoSize(videoSize));

      Surface surface = clientView.getSurface();

      final long PACKET_FLAG_CONFIG = 0x8000000000000000L;
      final long PACKET_FLAG_KEY_FRAME = 0x4000000000000000L;

      byte[] csd0 = null;
      byte[] csd1 = null;

      // 循环处理报文
      while (!Thread.interrupted()) {
        long ptsAndFlags = videoStream.readLong();
        int packetSize = videoStream.readInt();
        lastKeepAliveTime = System.currentTimeMillis();
        byte[] data = videoStream.readByteArray(packetSize).array();

        boolean config = (ptsAndFlags & PACKET_FLAG_CONFIG) != 0;
        long pts = config ? 0 : (ptsAndFlags & ~(PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME));

        if (videoDecode == null) {
          if (!config) continue;

          int h264Id = 0x68323634;
          int h265Id = 0x68323635;
          boolean isH265 = codecId == h265Id;

          byte[] sps = null;
          byte[] pps = null;
          byte[] vps = null;

          int i = 0;
          while (i < data.length) {
            int start = findAnnexBStartCode(data, i);
            if (start < 0) break;
            int prefixLen = (start + 2 < data.length && data[start] == 0 && data[start + 1] == 0 && data[start + 2] == 1) ? 3 : 4;
            int next = findAnnexBStartCode(data, start + prefixLen);
            if (next < 0) next = data.length;
            int nalHeaderIndex = start + prefixLen;
            if (nalHeaderIndex >= next) {
              i = next;
              continue;
            }

            int nalType;
            if (isH265) {
              nalType = (data[nalHeaderIndex] >> 1) & 0x3F;
            } else {
              nalType = data[nalHeaderIndex] & 0x1F;
            }

            byte[] nal = new byte[next - start];
            System.arraycopy(data, start, nal, 0, nal.length);

            if (!isH265) {
              if (nalType == 7) sps = nal;
              else if (nalType == 8) pps = nal;
            } else {
              if (nalType == 32) vps = nal;
              else if (nalType == 33) sps = nal;
              else if (nalType == 34) pps = nal;
            }

            i = next;
          }

          if (isH265) {
            ByteBuffer csdBuf = ByteBuffer.allocate((vps != null ? vps.length : 0) + (sps != null ? sps.length : 0) + (pps != null ? pps.length : 0));
            if (vps != null) csdBuf.put(vps);
            if (sps != null) csdBuf.put(sps);
            if (pps != null) csdBuf.put(pps);
            csd0 = csdBuf.array();
            csd1 = null;
          } else {
            csd0 = sps != null ? sps : data;
            csd1 = pps;
          }

          videoDecode = new VideoDecode(videoSize, surface, new Pair<>(csd0, 0L), csd1 == null ? null : new Pair<>(csd1, 0L), handler);
          continue;
        }

        if (config) {
          continue;
        }
        videoDecode.decodeIn(data, pts);
      }
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  private void executeStreamIn() {
    try {
      while (status != -1 && bufferStream == null) Thread.sleep(50);
      if (status == -1 || bufferStream == null) return;

      while (!Thread.interrupted()) {
        byte type = bufferStream.readByte();
        lastKeepAliveTime = System.currentTimeMillis();
        if (type == 0) {
          int len = bufferStream.readInt();
          controlPacket.nowClipboardText = new String(bufferStream.readByteArray(len).array(), StandardCharsets.UTF_8);
          if (clientView.device.clipboardSync) {
            AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(MIMETYPE_TEXT_PLAIN, controlPacket.nowClipboardText));
          }
        } else if (type == 1) {
          bufferStream.readLong();
        }
      }
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  private void executeStreamAudio() {
    try {
      while (status != -1 && audioStream == null) Thread.sleep(50);
      if (status == -1 || audioStream == null) return;

      int codecId = audioStream.readInt();
      if (codecId <= 1) {
        return;
      }
      boolean useOpus = codecId == 0x6f707573;

      final long PACKET_FLAG_CONFIG = 0x8000000000000000L;
      final long PACKET_FLAG_KEY_FRAME = 0x4000000000000000L;

      byte[] csd0 = null;

      while (!Thread.interrupted()) {
        long ptsAndFlags = audioStream.readLong();
        int packetSize = audioStream.readInt();
        lastKeepAliveTime = System.currentTimeMillis();
        byte[] data = audioStream.readByteArray(packetSize).array();
        boolean config = (ptsAndFlags & PACKET_FLAG_CONFIG) != 0;

        if (audioDecode == null) {
          if (!config) continue;
          csd0 = data;
          audioDecode = new AudioDecode(useOpus, csd0, handler);
          if (multiLink != 2) playAudio(true);
          continue;
        }

        if (config) {
          continue;
        }
        audioDecode.decodeIn(data);
      }
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  private static int findAnnexBStartCode(byte[] data, int from) {
    for (int i = Math.max(0, from); i + 3 < data.length; i++) {
      if (data[i] == 0 && data[i + 1] == 0) {
        if (data[i + 2] == 1) return i;
        if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) return i;
      }
    }
    return -1;
  }

  private void executeOtherService() {
    if (status == 1) {
      if (clientView.device.clipboardSync) controlPacket.checkClipBoard();
      controlPacket.sendKeepAlive();
      AppData.uiHandler.postDelayed(this::executeOtherService, 1500);
    }
  }

  private void write(ByteBuffer byteBuffer) {
    try {
      bufferStream.write(byteBuffer);
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  public void release(String error) {
    if (status == -1) return;
    status = -1;
    allClient.remove(this);
    if (error != null) {
      PublicTools.logToast(error);
      if (AppData.setting.getShowReconnect())
        AppData.uiHandler.postDelayed(() -> AppData.myBroadcastReceiver.handleReconnect(clientView.deviceOriginal, mode), 500);
    }
    for (int i = 0; i < 7; i++) {
      try {
        switch (i) {
          case 0:
            tryRestoreSourceDeviceVolume();
            if (displayId != 0) {
              Adb.getStringResponseFromServer(clientView.device, "releaseVirtualDisplay", "id=" + displayId);
            }
            break;
          case 1:
            if (multiLink == 1) {
              Client target = null;
              boolean multi = false;
              for (Client client : allClient) {
                if (client.uuid.equals(uuid) && client.multiLink == 2) {
                  if (target != null) {
                    multi = true;
                    break;
                  }
                  target = client;
                }
              }
              if (target != null) {
                if (multi) target.changeMultiLinkMode(1);
                else target.changeMultiLinkMode(0);
              }
            }
            break;
          case 2:
            if (loggerThread != null) loggerThread.interrupt();
            String log = new String(shell.readAllBytes().array(), StandardCharsets.UTF_8);
            if (!log.isEmpty()) L.logWithoutTime(uuid, log);
            break;
          case 3:
            keepAliveThread.interrupt();
            executeStreamInThread.interrupt();
            executeStreamVideoThread.interrupt();
            executeStreamAudioThread.interrupt();
            if (handlerThread != null) handlerThread.quit();
            break;
          case 4:
            AppData.uiHandler.post(() -> clientView.hide(true));
            break;
          case 5:
            if (bufferStream != null) bufferStream.close();
            if (videoStream != null) videoStream.close();
            if (audioStream != null) audioStream.close();
            break;
          case 6:
            if (videoDecode != null) videoDecode.release();
            if (audioDecode != null) audioDecode.release();
            break;
        }
      } catch (Exception ignored) {
      }
    }
  }

  public static void runOnceCmd(Device device, UsbDevice usbDevice, String cmd, PublicTools.MyFunctionBoolean handle) {
    new Thread(() -> {
      try {
        Adb adb = connectADB(device, usbDevice);
        adb.runAdbCmd(cmd);
        handle.run(true);
      } catch (Exception ignored) {
        handle.run(false);
      }
    }).start();
  }

  public static ArrayList<String> getAppList(Device device, UsbDevice usbDevice) {
    try {
      if (Adb.adbMap.get(device.uuid) == null) {
        if (device.isLinkDevice()) Adb.adbMap.put(device.uuid, new Adb(device.uuid, usbDevice, AppData.keyPair));
        else Adb.adbMap.put(device.uuid, new Adb(device.uuid, device.address, AppData.keyPair));
      }
      ArrayList<String> appList = new ArrayList<>();
      String output = Adb.getStringResponseFromServer(device, "getAllAppInfo", "app_type=1");
      String[] allAppInfo = output.split("<!@n@!>");
      for (String info : allAppInfo) {
        String[] appInfo = info.split("<!@r@!>");
        if (appInfo.length > 1) appList.add(appInfo[1] + "@" + appInfo[0]);
      }
      return appList;
    } catch (Exception e) {
      L.log(device.uuid, e);
      return new ArrayList<>();
    }
  }

  public static void restartOnTcpip(Device device, UsbDevice usbDevice, PublicTools.MyFunctionBoolean handle) {
    new Thread(() -> {
      try {
        Adb adb = connectADB(device, usbDevice);
        String output = adb.restartOnTcpip(5555);
        handle.run(output.contains("restarting"));
      } catch (Exception ignored) {
        handle.run(false);
      }
    }).start();
  }

  // 检查是否启动完成
  public boolean isStarted() {
    return status == 1 && clientView != null;
  }

  public boolean isClosed() {
    return status == -1 || clientView == null;
  }

  public void changeMode(int mode) {
    if (this.mode == mode) return;
    this.mode = mode;
    clientView.changeSizeLock.set(false);
    if (mode == 0) {
      try {
        Adb.getStringResponseFromServer(clientView.device, "releaseVirtualDisplay", "id=" + displayId);
      } catch (Exception ignored) {
      }
      displayId = 0;
      clientView.displayId = 0;
    } else if (mode == 1) {
      tryCreateDisplay(clientView.device);
      if (displayId == 0) return;
    }
    new Thread(() -> {
      try {
        while (!isStarted()) {
          Thread.sleep(1000);
        }
        controlPacket.sendConfigChangedEvent(-displayId);
        if (mode != 0) appTransfer(clientView.device);
        synchronized (clientView.changeSizeLock) {
          clientView.changeSizeLock.set(true);
          clientView.changeSizeLock.notifyAll();
        }
      } catch (Exception ignored) {
      }
    }).start();
    clientView.changeMode(mode);
  }

  public void changeMultiLinkMode(int multiLink) {
    playAudio(multiLink == 0 || multiLink == 1);
    if (multiLink == 2) {
      clientView.device.clipboardSync = false;
      clientView.device.nightModeSync = false;
    } else if (multiLink == 0 || multiLink == 1) {
      if (clientView.deviceOriginal.clipboardSync) clientView.device.clipboardSync = true;
      if (clientView.deviceOriginal.nightModeSync) clientView.device.nightModeSync = true;
    }
    this.multiLink = multiLink;
    clientView.multiLink = multiLink;
  }

  public void playAudio(boolean play) {
    if (audioDecode != null) audioDecode.playAudio(play);
  }
}
