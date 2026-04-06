package com.kajsiebert.mimir.openai.rtp;

public class ByteRingBuffer {
  private final byte[] buffer;
  private int head = 0;
  private int tail = 0;
  private int size = 0;

  public ByteRingBuffer(int capacity) {
    buffer = new byte[capacity];
  }

  public boolean write(byte[] data) {
    if (data.length > buffer.length - size) return false;

    for (byte b : data) {
      buffer[tail] = b;
      tail = (tail + 1) % buffer.length;
    }
    size += data.length;
    return true;
  }

  public byte[] pop(int len) {
    if (size < len) return null;

    byte[] result = new byte[len];
    for (int i = 0; i < len; i++) {
      result[i] = buffer[head];
      head = (head + 1) % buffer.length;
    }
    size -= len;
    return result;
  }

  public int size() {
    return size;
  }
}
