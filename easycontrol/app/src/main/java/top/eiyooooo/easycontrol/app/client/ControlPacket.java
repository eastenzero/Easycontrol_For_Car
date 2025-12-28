package top.eiyooooo.easycontrol.app.client;

import android.content.ClipData;
import android.view.MotionEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.buffer.BufferStream;

public class ControlPacket {
  private final MyFunctionByteBuffer write;

  private static final byte SC_CONTROL_MSG_TYPE_INJECT_KEYCODE = 0;
  private static final byte SC_CONTROL_MSG_TYPE_INJECT_TEXT = 1;
  private static final byte SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT = 2;
  private static final byte SC_CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT = 3;
  private static final byte SC_CONTROL_MSG_TYPE_BACK_OR_SCREEN_ON = 4;
  private static final byte SC_CONTROL_MSG_TYPE_EXPAND_NOTIFICATION_PANEL = 5;
  private static final byte SC_CONTROL_MSG_TYPE_EXPAND_SETTINGS_PANEL = 6;
  private static final byte SC_CONTROL_MSG_TYPE_COLLAPSE_PANELS = 7;
  private static final byte SC_CONTROL_MSG_TYPE_GET_CLIPBOARD = 8;
  private static final byte SC_CONTROL_MSG_TYPE_SET_CLIPBOARD = 9;
  private static final byte SC_CONTROL_MSG_TYPE_SET_SCREEN_POWER_MODE = 10;
  private static final byte SC_CONTROL_MSG_TYPE_ROTATE_DEVICE = 11;

  private static final byte SC_SCREEN_POWER_MODE_OFF = 0;
  private static final byte SC_SCREEN_POWER_MODE_NORMAL = 2;

  private int screenWidth;
  private int screenHeight;
  private long clipboardSequence;

  public ControlPacket(MyFunctionByteBuffer write) {
    this.write = write;
  }

  public void setScreenSize(int width, int height) {
    this.screenWidth = width;
    this.screenHeight = height;
  }

  public byte[] readFrame(BufferStream bufferStream) throws IOException, InterruptedException {
    return bufferStream.readByteArray(bufferStream.readInt()).array();
  }

  // 剪切板
  public String nowClipboardText = "";

  public void checkClipBoard() {
    ClipData clipBoard = AppData.clipBoard.getPrimaryClip();
    if (clipBoard != null && clipBoard.getItemCount() > 0) {
      String newClipBoardText = String.valueOf(clipBoard.getItemAt(0).getText());
      if (!newClipBoardText.equals(nowClipboardText)) {
        nowClipboardText = newClipBoardText;
        sendClipboardEvent();
      }
    }
  }

  // 发送触摸事件
  public void sendTouchEvent(int action, int p, float x, float y, int offsetTime) {
    if (screenWidth <= 0 || screenHeight <= 0) return;

    if (x < 0 || x > 1 || y < 0 || y > 1) {
      if (x < 0) x = 0;
      if (x > 1) x = 1;
      if (y < 0) y = 0;
      if (y > 1) y = 1;
      action = MotionEvent.ACTION_UP;
    }

    int px = (int) (x * screenWidth);
    int py = (int) (y * screenHeight);
    if (px < 0) px = 0;
    if (px > screenWidth - 1) px = screenWidth - 1;
    if (py < 0) py = 0;
    if (py > screenHeight - 1) py = screenHeight - 1;

    float pressureFloat = action == MotionEvent.ACTION_UP ? 0f : 1f;
    short pressure = scFloatToU16fp(pressureFloat);

    ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
    buf.put(SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT);
    buf.put((byte) action);
    buf.putLong((long) p);
    buf.putInt(px);
    buf.putInt(py);
    buf.putShort((short) screenWidth);
    buf.putShort((short) screenHeight);
    buf.putShort(pressure);
    buf.putInt(0);
    buf.putInt(0);
    buf.flip();
    write.run(buf);
  }

  // 发送按键事件
  public void sendKeyEvent(int key, int meta, int displayIdToInject) {
    sendKeyEventInternal((byte) 0, key, meta);
    sendKeyEventInternal((byte) 1, key, meta);
  }

  private void sendKeyEventInternal(byte action, int key, int meta) {
    ByteBuffer buf = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
    buf.put(SC_CONTROL_MSG_TYPE_INJECT_KEYCODE);
    buf.put(action);
    buf.putInt(key);
    buf.putInt(0);
    buf.putInt(meta);
    buf.flip();
    write.run(buf);
  }

  // 发送剪切板事件
  private void sendClipboardEvent() {
    byte[] tmpTextByte = nowClipboardText.getBytes(StandardCharsets.UTF_8);
    if (tmpTextByte.length == 0 || tmpTextByte.length > 5000) return;
    long seq = ++clipboardSequence;
    ByteBuffer buf = ByteBuffer.allocate(14 + tmpTextByte.length).order(ByteOrder.BIG_ENDIAN);
    buf.put(SC_CONTROL_MSG_TYPE_SET_CLIPBOARD);
    buf.putLong(seq);
    buf.put((byte) 0);
    buf.putInt(tmpTextByte.length);
    buf.put(tmpTextByte);
    buf.flip();
    write.run(buf);
  }

  // 发送心跳包
  public void sendKeepAlive() {
  }

  // 发送更新事件
  public void sendConfigChangedEvent(int mode) {
  }

  // 发送旋转请求事件
  public void sendRotateEvent() {
    sendRotateEvent(-1);
  }

  public void sendRotateEvent(int rotation) {
    write.run(ByteBuffer.wrap(new byte[]{SC_CONTROL_MSG_TYPE_ROTATE_DEVICE}));
  }

  // 发送背光控制事件
  public void sendLightEvent(int mode) {
    byte m = (mode == 2 || mode == 1) ? SC_SCREEN_POWER_MODE_NORMAL : SC_SCREEN_POWER_MODE_OFF;
    write.run(ByteBuffer.wrap(new byte[]{SC_CONTROL_MSG_TYPE_SET_SCREEN_POWER_MODE, m}));
  }

  // 发送电源键事件
  public void sendPowerEvent() {
    sendKeyEvent(26, 0, -1);
  }

  // 发送黑暗模式事件
  public void sendNightModeEvent(int mode) {
  }

  private static short scFloatToU16fp(float f) {
    if (f < 0f) f = 0f;
    if (f > 1f) f = 1f;
    int u = (int) (f * 65536f);
    if (u >= 0xFFFF) u = 0xFFFF;
    return (short) u;
  }

  @SuppressWarnings("unused")
  private static short scFloatToI16fp(float f) {
    if (f < -1f) f = -1f;
    if (f > 1f) f = 1f;
    int i = (int) (f * 32768f);
    if (i < -0x8000) i = -0x8000;
    if (i >= 0x7FFF) i = 0x7FFF;
    return (short) i;
  }

  public interface MyFunctionByteBuffer {
    void run(ByteBuffer byteBuffer);
  }
}
