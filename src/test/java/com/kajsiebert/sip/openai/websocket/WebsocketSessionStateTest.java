package com.kajsiebert.sip.openai.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebsocketSessionState Tests")
class WebsocketSessionStateTest {

  @Test
  @DisplayName("Should have all expected enum values")
  void shouldHaveAllExpectedEnumValues() {
    WebsocketSessionState[] expectedStates = {
      WebsocketSessionState.NEW,
      WebsocketSessionState.CONNECTED,
      WebsocketSessionState.SESSION_CREATED,
      WebsocketSessionState.ANSWERED,
      WebsocketSessionState.AUDIO_RECEIVED,
      WebsocketSessionState.TERMINATED
    };

    WebsocketSessionState[] actualStates = WebsocketSessionState.values();

    assertThat(actualStates).containsExactly(expectedStates);
  }

  @Test
  @DisplayName("Should have correct enum count")
  void shouldHaveCorrectEnumCount() {
    assertThat(WebsocketSessionState.values()).hasSize(6);
  }

  @Test
  @DisplayName("Should support valueOf for all states")
  void shouldSupportValueOfForAllStates() {
    assertThat(WebsocketSessionState.valueOf("NEW")).isEqualTo(WebsocketSessionState.NEW);
    assertThat(WebsocketSessionState.valueOf("CONNECTED"))
        .isEqualTo(WebsocketSessionState.CONNECTED);
    assertThat(WebsocketSessionState.valueOf("SESSION_CREATED"))
        .isEqualTo(WebsocketSessionState.SESSION_CREATED);
    assertThat(WebsocketSessionState.valueOf("ANSWERED")).isEqualTo(WebsocketSessionState.ANSWERED);
    assertThat(WebsocketSessionState.valueOf("AUDIO_RECEIVED"))
        .isEqualTo(WebsocketSessionState.AUDIO_RECEIVED);
    assertThat(WebsocketSessionState.valueOf("TERMINATED"))
        .isEqualTo(WebsocketSessionState.TERMINATED);
  }

  @Test
  @DisplayName("Should have correct ordinal values")
  void shouldHaveCorrectOrdinalValues() {
    assertThat(WebsocketSessionState.NEW.ordinal()).isZero();
    assertThat(WebsocketSessionState.CONNECTED.ordinal()).isEqualTo(1);
    assertThat(WebsocketSessionState.SESSION_CREATED.ordinal()).isEqualTo(2);
    assertThat(WebsocketSessionState.ANSWERED.ordinal()).isEqualTo(3);
    assertThat(WebsocketSessionState.AUDIO_RECEIVED.ordinal()).isEqualTo(4);
    assertThat(WebsocketSessionState.TERMINATED.ordinal()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should have correct string representation")
  void shouldHaveCorrectStringRepresentation() {
    assertThat(WebsocketSessionState.NEW.toString()).isEqualTo("NEW");
    assertThat(WebsocketSessionState.CONNECTED.toString()).isEqualTo("CONNECTED");
    assertThat(WebsocketSessionState.SESSION_CREATED.toString()).isEqualTo("SESSION_CREATED");
    assertThat(WebsocketSessionState.ANSWERED.toString()).isEqualTo("ANSWERED");
    assertThat(WebsocketSessionState.AUDIO_RECEIVED.toString()).isEqualTo("AUDIO_RECEIVED");
    assertThat(WebsocketSessionState.TERMINATED.toString()).isEqualTo("TERMINATED");
  }
}
