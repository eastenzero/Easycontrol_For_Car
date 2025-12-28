package top.eiyooooo.easycontrol.app.client;

import java.nio.ByteBuffer;

public class AudioPlayer {
  static {
    System.loadLibrary("native-lib");
  }

  private long nativeHandle;

  public AudioPlayer(int sampleRate, int channelCount) {
    nativeHandle = nativeCreate(sampleRate, channelCount);
    nativeStart(nativeHandle);
    nativeSetPlaying(nativeHandle, false);
  }

  public void setPlaying(boolean playing) {
    nativeSetPlaying(nativeHandle, playing);
  }

  public int write(ByteBuffer buffer, int size) {
    return nativeWrite(nativeHandle, buffer, size);
  }

  public native void writeAudio(byte[] data, int size);

  public void stop() {
    nativeStop(nativeHandle);
  }

  public void release() {
    nativeRelease(nativeHandle);
    nativeHandle = 0;
  }

  private static native long nativeCreate(int sampleRate, int channelCount);

  private static native boolean nativeStart(long handle);

  private static native void nativeStop(long handle);

  private static native void nativeSetPlaying(long handle, boolean playing);

  private static native int nativeWrite(long handle, ByteBuffer buffer, int size);

  private static native void nativeRelease(long handle);
}
