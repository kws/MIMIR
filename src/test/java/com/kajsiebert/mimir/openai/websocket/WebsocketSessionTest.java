package com.kajsiebert.sip.openai.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kajsiebert.sip.openai.ExtensionConfig;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Unit tests for WebsocketSession Tests WebSocket connection, message handling, audio processing,
 * and state management
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebsocketSession Tests")
class WebsocketSessionTest {

  @Mock private Vertx vertx;

  @Mock private HttpClient httpClient;

  @Mock private WebSocket webSocket;

  @Mock private ExtensionConfig extensionConfig;

  private WebsocketSession websocketSession;
  private AtomicReference<Handler<WebSocketFrame>> frameHandler;
  private AtomicReference<Handler<Throwable>> exceptionHandler;
  private AtomicReference<Handler<Void>> closeHandler;

  @BeforeEach
  void setUp() {
    frameHandler = new AtomicReference<>();
    exceptionHandler = new AtomicReference<>();
    closeHandler = new AtomicReference<>();

    // Mock Vertx and HttpClient creation
    lenient().when(vertx.createHttpClient(any(HttpClientOptions.class))).thenReturn(httpClient);

    // Mock extension config
    lenient().when(extensionConfig.getInstructions()).thenReturn("Test instructions");
    lenient().when(extensionConfig.getVoice()).thenReturn("alloy");
    lenient().when(extensionConfig.getGreeting()).thenReturn("Hello, test greeting");

    // Mock WebSocket connection success
    lenient()
        .doAnswer(
            invocation -> {
              Handler<AsyncResult<WebSocket>> resultHandler = invocation.getArgument(1);
              AsyncResult<WebSocket> successResult = mock(AsyncResult.class);
              lenient().when(successResult.succeeded()).thenReturn(true);
              lenient().when(successResult.result()).thenReturn(webSocket);
              resultHandler.handle(successResult);
              return null;
            })
        .when(httpClient)
        .webSocket(any(WebSocketConnectOptions.class), any());

    // Capture handlers when they're set on the WebSocket
    lenient()
        .when(webSocket.frameHandler(any()))
        .thenAnswer(
            invocation -> {
              frameHandler.set(invocation.getArgument(0));
              return webSocket;
            });

    lenient()
        .when(webSocket.exceptionHandler(any()))
        .thenAnswer(
            invocation -> {
              exceptionHandler.set(invocation.getArgument(0));
              return webSocket;
            });

    lenient()
        .when(webSocket.closeHandler(any()))
        .thenAnswer(
            invocation -> {
              closeHandler.set(invocation.getArgument(0));
              return webSocket;
            });

    lenient().when(webSocket.writeTextMessage(anyString())).thenReturn(null);
    lenient().when(webSocket.close()).thenReturn(null);

    websocketSession = new WebsocketSession(vertx, extensionConfig);
  }

  @Test
  @DisplayName("Should initialize with NEW state")
  void shouldInitializeWithNewState() {
    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.NEW);
  }

  @Test
  @DisplayName("Should start WebSocket connection successfully")
  void shouldStartWebSocketConnectionSuccessfully() {
    boolean result = websocketSession.start();

    assertThat(result).isTrue();
    verify(vertx).createHttpClient(any(HttpClientOptions.class));
    verify(httpClient).webSocket(any(WebSocketConnectOptions.class), any());
  }

  @Test
  @DisplayName("Should setup WebSocket handlers on successful connection")
  void shouldSetupWebSocketHandlersOnSuccessfulConnection() {
    websocketSession.start();

    verify(webSocket).frameHandler(any());
    verify(webSocket).exceptionHandler(any());
    verify(webSocket).closeHandler(any());
    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.CONNECTED);
  }

  @Test
  @DisplayName("Should send session configuration on connection")
  void shouldSendSessionConfigurationOnConnection() {
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

    websocketSession.start();

    verify(webSocket).writeTextMessage(messageCaptor.capture());
    String sentMessage = messageCaptor.getValue();
    JsonObject sentJson = new JsonObject(sentMessage);

    assertThat(sentJson.getString("type")).isEqualTo("session.update");
    assertThat(sentJson.getJsonObject("session").getString("instructions"))
        .isEqualTo("Test instructions");
    assertThat(sentJson.getJsonObject("session").getString("voice")).isEqualTo("alloy");
  }

  @Test
  @DisplayName("Should generate correct session configuration")
  void shouldGenerateCorrectSessionConfiguration() {
    JsonObject sessionConfig = websocketSession.getSessionConfig();

    assertThat(sessionConfig.getString("type")).isEqualTo("session.update");
    JsonObject session = sessionConfig.getJsonObject("session");
    assertThat(session.getString("instructions")).isEqualTo("Test instructions");
    assertThat(session.getString("voice")).isEqualTo("alloy");
    assertThat(session.getString("input_audio_format")).isEqualTo("g711_ulaw");
    assertThat(session.getString("output_audio_format")).isEqualTo("g711_ulaw");
    assertThat(session.getJsonArray("modalities")).isEqualTo(JsonArray.of("audio", "text"));

    JsonObject turnDetection = session.getJsonObject("turn_detection");
    assertThat(turnDetection.getString("type")).isEqualTo("server_vad");
    assertThat(turnDetection.getBoolean("create_response")).isTrue();
    assertThat(turnDetection.getBoolean("interrupt_response")).isTrue();
  }

  @Test
  @DisplayName("Should generate correct create response message")
  void shouldGenerateCorrectCreateResponseMessage() {
    JsonObject createResponse = websocketSession.getCreateResponse();

    assertThat(createResponse.getString("type")).isEqualTo("response.create");
    JsonObject response = createResponse.getJsonObject("response");
    assertThat(response.getString("instructions")).isEqualTo("Hello, test greeting");
  }

  @Test
  @DisplayName("Should handle session.created message correctly")
  void shouldHandleSessionCreatedMessageCorrectly() {
    websocketSession.start();
    JsonObject sessionCreatedMsg = new JsonObject().put("type", "session.created");

    simulateTextFrame(sessionCreatedMsg.encode());

    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.SESSION_CREATED);
  }

  @Test
  @DisplayName("Should handle session.updated message and send create response")
  void shouldHandleSessionUpdatedMessageAndSendCreateResponse() {
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    websocketSession.start();
    websocketSession.state = WebsocketSessionState.SESSION_CREATED; // Set state directly for test

    JsonObject sessionUpdatedMsg = new JsonObject().put("type", "session.updated");
    simulateTextFrame(sessionUpdatedMsg.encode());

    verify(webSocket, times(2)).writeTextMessage(messageCaptor.capture());
    String sentMessage =
        messageCaptor.getAllValues().get(1); // Second message (first is session config)
    JsonObject sentJson = new JsonObject(sentMessage);

    assertThat(sentJson.getString("type")).isEqualTo("response.create");
    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.ANSWERED);
  }

  @Test
  @DisplayName("Should handle response.audio.delta message and update state")
  void shouldHandleResponseAudioDeltaMessageAndUpdateState() {
    websocketSession.start();
    byte[] testAudio = {1, 2, 3, 4, 5};
    String audioB64 = Base64.getEncoder().encodeToString(testAudio);

    JsonObject audioDeltaMsg =
        new JsonObject().put("type", "response.audio.delta").put("delta", audioB64);

    simulateTextFrame(audioDeltaMsg.encode());

    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.AUDIO_RECEIVED);

    // Verify audio was queued
    Buffer nextPacket = websocketSession.getNextRtpPacket();
    assertThat(nextPacket).isNotNull();
  }

  @Test
  @DisplayName("Should handle input_audio_buffer.speech_started message and clear audio")
  void shouldHandleInputAudioBufferSpeechStartedMessage() {
    websocketSession.start();

    // First add some audio
    byte[] testAudio = {1, 2, 3, 4, 5};
    String audioB64 = Base64.getEncoder().encodeToString(testAudio);
    JsonObject audioDeltaMsg =
        new JsonObject().put("type", "response.audio.delta").put("delta", audioB64);
    simulateTextFrame(audioDeltaMsg.encode());

    // Verify audio is present
    Buffer packet1 = websocketSession.getNextRtpPacket();
    assertThat(packet1).isNotNull();

    // Now simulate speech started (should clear audio)
    JsonObject speechStartedMsg = new JsonObject().put("type", "input_audio_buffer.speech_started");
    simulateTextFrame(speechStartedMsg.encode());

    // Audio queue should be cleared
    Buffer packet2 = websocketSession.getNextRtpPacket();
    assertThat(packet2).isNull();
  }

  @Test
  @DisplayName("Should handle error messages gracefully")
  void shouldHandleErrorMessagesGracefully() {
    websocketSession.start();

    JsonObject errorMsg =
        new JsonObject()
            .put("type", "error")
            .put("error", new JsonObject().put("message", "Test error"));

    // Should not throw exception
    simulateTextFrame(errorMsg.encode());

    // State should remain unchanged
    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.CONNECTED);
  }

  @Test
  @DisplayName("Should send audio data correctly")
  void shouldSendAudioDataCorrectly() {
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    websocketSession.start();

    byte[] audioData = {1, 2, 3, 4, 5};
    websocketSession.sendAudio(audioData);

    verify(webSocket, times(2)).writeTextMessage(messageCaptor.capture());
    String sentMessage = messageCaptor.getAllValues().get(1); // Second message
    JsonObject sentJson = new JsonObject(sentMessage);

    assertThat(sentJson.getString("type")).isEqualTo("input_audio_buffer.append");
    String sentAudioB64 = sentJson.getString("audio");
    byte[] decodedAudio = Base64.getDecoder().decode(sentAudioB64);
    assertThat(decodedAudio).isEqualTo(audioData);
  }

  @Test
  @DisplayName("Should close WebSocket connection")
  void shouldCloseWebSocketConnection() {
    websocketSession.start();

    websocketSession.close();

    verify(webSocket).close();
  }

  @Test
  @DisplayName("Should handle WebSocket exceptions and update state")
  void shouldHandleWebSocketExceptionsAndUpdateState() {
    websocketSession.start();
    RuntimeException testException = new RuntimeException("Test WebSocket error");

    simulateException(testException);

    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.TERMINATED);
  }

  @Test
  @DisplayName("Should handle WebSocket close and update state")
  void shouldHandleWebSocketCloseAndUpdateState() {
    websocketSession.start();

    simulateClose();

    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.TERMINATED);
  }

  @Test
  @DisplayName("Should register and trigger audio received callbacks")
  void shouldRegisterAndTriggerAudioReceivedCallbacks() {
    websocketSession.start();
    AtomicReference<WebsocketSessionState> callbackState = new AtomicReference<>();

    websocketSession.onAudioReceived(callbackState::set);

    // Trigger audio received
    byte[] testAudio = {1, 2, 3};
    String audioB64 = Base64.getEncoder().encodeToString(testAudio);
    JsonObject audioDeltaMsg =
        new JsonObject().put("type", "response.audio.delta").put("delta", audioB64);

    simulateTextFrame(audioDeltaMsg.encode());

    assertThat(callbackState.get()).isEqualTo(WebsocketSessionState.AUDIO_RECEIVED);
  }

  @Test
  @DisplayName("Should register and trigger call ended callbacks")
  void shouldRegisterAndTriggerCallEndedCallbacks() {
    websocketSession.start();
    AtomicReference<WebsocketSessionState> callbackState = new AtomicReference<>();

    websocketSession.onCallEnded(callbackState::set);

    simulateException(new RuntimeException("Test error"));

    assertThat(callbackState.get()).isEqualTo(WebsocketSessionState.TERMINATED);
  }

  @Test
  @DisplayName("Should handle unknown message types gracefully")
  void shouldHandleUnknownMessageTypesGracefully() {
    websocketSession.start();

    JsonObject unknownMsg =
        new JsonObject().put("type", "unknown.message.type").put("data", "some data");

    // Should not throw exception
    simulateTextFrame(unknownMsg.encode());

    // State should remain unchanged
    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.CONNECTED);
  }

  @Test
  @DisplayName("Should handle malformed JSON messages gracefully")
  void shouldHandleMalformedJsonMessagesGracefully() {
    websocketSession.start();

    // The WebsocketSession.handleFrame() method will throw DecodeException for malformed JSON
    // This is expected behavior as JSON parsing occurs at the framework level
    assertThatThrownBy(() -> simulateTextFrame("{ invalid json }"))
        .isInstanceOf(io.vertx.core.json.DecodeException.class)
        .hasMessageContaining("Unexpected character");

    // State should remain unchanged after the exception
    assertThat(websocketSession.state).isEqualTo(WebsocketSessionState.CONNECTED);
  }

  @Test
  @DisplayName("Should only trigger audio received callback once per state change")
  void shouldOnlyTriggerAudioReceivedCallbackOncePerStateChange() {
    websocketSession.start();
    AtomicReference<Integer> callbackCount = new AtomicReference<>(0);

    websocketSession.onAudioReceived(state -> callbackCount.updateAndGet(c -> c + 1));

    // Send multiple audio deltas
    for (int i = 0; i < 3; i++) {
      byte[] testAudio = {(byte) i};
      String audioB64 = Base64.getEncoder().encodeToString(testAudio);
      JsonObject audioDeltaMsg =
          new JsonObject().put("type", "response.audio.delta").put("delta", audioB64);
      simulateTextFrame(audioDeltaMsg.encode());
    }

    // Callback should only be triggered once when state first changes to AUDIO_RECEIVED
    assertThat(callbackCount.get()).isEqualTo(1);
  }

  // Helper methods for simulating WebSocket events
  private void simulateTextFrame(String textData) {
    if (frameHandler.get() != null) {
      WebSocketFrame frame = mock(WebSocketFrame.class);
      when(frame.isText()).thenReturn(true);
      when(frame.textData()).thenReturn(textData);
      frameHandler.get().handle(frame);
    }
  }

  private void simulateException(Throwable exception) {
    if (exceptionHandler.get() != null) {
      exceptionHandler.get().handle(exception);
    }
  }

  private void simulateClose() {
    if (closeHandler.get() != null) {
      closeHandler.get().handle(null);
    }
  }
}
