package com.kajsiebert.sip.openai.rtp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ByteRingBuffer Tests")
class ByteRingBufferTest {

  private ByteRingBuffer buffer;
  private static final int CAPACITY = 10;

  @BeforeEach
  void setUp() {
    buffer = new ByteRingBuffer(CAPACITY);
  }

  @Test
  @DisplayName("Should create empty buffer with correct initial state")
  void shouldCreateEmptyBuffer() {
    assertThat(buffer.size()).isZero();
  }

  @Test
  @DisplayName("Should write data when buffer has capacity")
  void shouldWriteDataWhenCapacityAvailable() {
    byte[] data = {1, 2, 3, 4, 5};

    boolean result = buffer.write(data);

    assertThat(result).isTrue();
    assertThat(buffer.size()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should reject write when data exceeds available capacity")
  void shouldRejectWriteWhenDataExceedsCapacity() {
    byte[] largeData = new byte[CAPACITY + 1];

    boolean result = buffer.write(largeData);

    assertThat(result).isFalse();
    assertThat(buffer.size()).isZero();
  }

  @Test
  @DisplayName("Should pop correct data in FIFO order")
  void shouldPopDataInFifoOrder() {
    byte[] data = {10, 20, 30, 40, 50};
    buffer.write(data);

    byte[] result = buffer.pop(3);

    assertThat(result).isEqualTo(new byte[] {10, 20, 30});
    assertThat(buffer.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should return null when trying to pop more data than available")
  void shouldReturnNullWhenPoppingMoreThanAvailable() {
    byte[] data = {1, 2, 3};
    buffer.write(data);

    byte[] result = buffer.pop(5);

    assertThat(result).isNull();
    assertThat(buffer.size()).isEqualTo(3); // Size should remain unchanged
  }

  @Test
  @DisplayName("Should handle circular buffer wrap-around correctly")
  void shouldHandleCircularWrapAround() {
    // Fill buffer to capacity
    byte[] initialData = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    buffer.write(initialData);

    // Pop some data to make room
    byte[] popped = buffer.pop(4);
    assertThat(popped).isEqualTo(new byte[] {1, 2, 3, 4});

    // Write new data that will wrap around
    byte[] newData = {11, 12, 13, 14};
    boolean writeResult = buffer.write(newData);

    assertThat(writeResult).isTrue();
    assertThat(buffer.size()).isEqualTo(CAPACITY);

    // Pop all remaining data and verify order
    byte[] allData = buffer.pop(CAPACITY);
    assertThat(allData).isEqualTo(new byte[] {5, 6, 7, 8, 9, 10, 11, 12, 13, 14});
  }

  @Test
  @DisplayName("Should handle multiple small writes and pops")
  void shouldHandleMultipleSmallOperations() {
    // Write some data
    buffer.write(new byte[] {1, 2});
    assertThat(buffer.size()).isEqualTo(2);

    // Write more data
    buffer.write(new byte[] {3, 4, 5});
    assertThat(buffer.size()).isEqualTo(5);

    // Pop some data
    byte[] popped1 = buffer.pop(2);
    assertThat(popped1).isEqualTo(new byte[] {1, 2});
    assertThat(buffer.size()).isEqualTo(3);

    // Write more data
    buffer.write(new byte[] {6, 7});
    assertThat(buffer.size()).isEqualTo(5);

    // Pop remaining data
    byte[] popped2 = buffer.pop(5);
    assertThat(popped2).isEqualTo(new byte[] {3, 4, 5, 6, 7});
    assertThat(buffer.size()).isZero();
  }

  @Test
  @DisplayName("Should handle empty operations")
  void shouldHandleEmptyOperations() {
    // Write empty array
    boolean writeResult = buffer.write(new byte[0]);
    assertThat(writeResult).isTrue();
    assertThat(buffer.size()).isZero();

    // Pop zero bytes
    byte[] popResult = buffer.pop(0);
    assertThat(popResult).isEqualTo(new byte[0]);
    assertThat(buffer.size()).isZero();
  }

  @Test
  @DisplayName("Should handle exact capacity operations")
  void shouldHandleExactCapacityOperations() {
    byte[] exactData = new byte[CAPACITY];
    for (int i = 0; i < CAPACITY; i++) {
      exactData[i] = (byte) (i + 1);
    }

    boolean writeResult = buffer.write(exactData);
    assertThat(writeResult).isTrue();
    assertThat(buffer.size()).isEqualTo(CAPACITY);

    byte[] popResult = buffer.pop(CAPACITY);
    assertThat(popResult).isEqualTo(exactData);
    assertThat(buffer.size()).isZero();
  }

  @Test
  @DisplayName("Should maintain correct state after failed operations")
  void shouldMaintainStateAfterFailedOperations() {
    byte[] initialData = {1, 2, 3};
    buffer.write(initialData);
    int initialSize = buffer.size();

    // Failed write
    byte[] largeData = new byte[CAPACITY];
    boolean writeResult = buffer.write(largeData);
    assertThat(writeResult).isFalse();
    assertThat(buffer.size()).isEqualTo(initialSize);

    // Failed pop
    byte[] popResult = buffer.pop(CAPACITY);
    assertThat(popResult).isNull();
    assertThat(buffer.size()).isEqualTo(initialSize);

    // Verify original data is still intact
    byte[] remaining = buffer.pop(3);
    assertThat(remaining).isEqualTo(initialData);
  }
}
