package com.kajsiebert.mimir.openai.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConsumerArray Tests")
class ConsumerArrayTest {

  private ConsumerArray<String> consumerArray;

  @BeforeEach
  void setUp() {
    consumerArray = new ConsumerArray<>();
  }

  @Test
  @DisplayName("Should accept and broadcast to single consumer")
  void shouldAcceptAndBroadcastToSingleConsumer() {
    List<String> receivedValues = new ArrayList<>();
    consumerArray.add(receivedValues::add);

    consumerArray.accept("test-value");

    assertThat(receivedValues).containsExactly("test-value");
  }

  @Test
  @DisplayName("Should broadcast to multiple consumers")
  void shouldBroadcastToMultipleConsumers() {
    List<String> consumer1Values = new ArrayList<>();
    List<String> consumer2Values = new ArrayList<>();
    List<String> consumer3Values = new ArrayList<>();

    consumerArray.add(consumer1Values::add);
    consumerArray.add(consumer2Values::add);
    consumerArray.add(consumer3Values::add);

    consumerArray.accept("broadcast-value");

    assertThat(consumer1Values).containsExactly("broadcast-value");
    assertThat(consumer2Values).containsExactly("broadcast-value");
    assertThat(consumer3Values).containsExactly("broadcast-value");
  }

  @Test
  @DisplayName("Should handle multiple accept calls")
  void shouldHandleMultipleAcceptCalls() {
    List<String> receivedValues = new ArrayList<>();
    consumerArray.add(receivedValues::add);

    consumerArray.accept("first");
    consumerArray.accept("second");
    consumerArray.accept("third");

    assertThat(receivedValues).containsExactly("first", "second", "third");
  }

  @Test
  @DisplayName("Should handle empty consumer list")
  void shouldHandleEmptyConsumerList() {
    // Should not throw exception when no consumers are added
    consumerArray.accept("ignored-value");
    // Test passes if no exception is thrown
  }

  @Test
  @DisplayName("Should work with different data types")
  void shouldWorkWithDifferentDataTypes() {
    ConsumerArray<Integer> intConsumerArray = new ConsumerArray<>();
    List<Integer> receivedInts = new ArrayList<>();
    intConsumerArray.add(receivedInts::add);

    intConsumerArray.accept(42);
    intConsumerArray.accept(100);

    assertThat(receivedInts).containsExactly(42, 100);
  }

  @Test
  @DisplayName("Should call each consumer exactly once per accept")
  void shouldCallEachConsumerExactlyOncePerAccept() {
    // Use counter to verify each consumer is called exactly once
    int[] counter1 = {0};
    int[] counter2 = {0};
    String[] receivedValue1 = {null};
    String[] receivedValue2 = {null};

    Consumer<String> consumer1 =
        value -> {
          counter1[0]++;
          receivedValue1[0] = value;
        };
    Consumer<String> consumer2 =
        value -> {
          counter2[0]++;
          receivedValue2[0] = value;
        };

    consumerArray.add(consumer1);
    consumerArray.add(consumer2);

    consumerArray.accept("test");

    assertThat(counter1[0]).isEqualTo(1);
    assertThat(counter2[0]).isEqualTo(1);
    assertThat(receivedValue1[0]).isEqualTo("test");
    assertThat(receivedValue2[0]).isEqualTo("test");
  }

  @Test
  @DisplayName("Should handle null values")
  void shouldHandleNullValues() {
    List<String> receivedValues = new ArrayList<>();
    consumerArray.add(receivedValues::add);

    consumerArray.accept(null);

    assertThat(receivedValues).containsExactly((String) null);
  }

  @Test
  @DisplayName("Should allow adding same consumer multiple times")
  void shouldAllowAddingSameConsumerMultipleTimes() {
    List<String> receivedValues = new ArrayList<>();
    Consumer<String> consumer = receivedValues::add;

    consumerArray.add(consumer);
    consumerArray.add(consumer);

    consumerArray.accept("duplicate-test");

    // Should receive the value twice since the same consumer was added twice
    assertThat(receivedValues).containsExactly("duplicate-test", "duplicate-test");
  }
}
