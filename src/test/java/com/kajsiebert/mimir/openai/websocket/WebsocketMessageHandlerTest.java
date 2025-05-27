package com.kajsiebert.mimir.openai.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vertx.core.json.JsonObject;

/**
 * Unit tests for WebsocketMessageHandler Tests reflection-based message handling, method
 * registration, and error handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebsocketMessageHandler Tests")
class WebsocketMessageHandlerTest {

  private TestableMessageHandler messageHandler;

  @BeforeEach
  void setUp() {
    messageHandler = new TestableMessageHandler();
  }

  @Test
  @DisplayName("Should register handlers for annotated methods during initialization")
  void shouldRegisterHandlersForAnnotatedMethods() {
    String[] registeredTypes = messageHandler.getRegisteredMessageTypes();

    assertThat(registeredTypes)
        .containsExactlyInAnyOrder(
            "test.message", "another.message", "private.handler", "exception.handler");
  }

  @Test
  @DisplayName("Should handle messages with registered handlers")
  void shouldHandleMessagesWithRegisteredHandlers() {
    JsonObject testMessage = new JsonObject().put("data", "test content");

    boolean handled = messageHandler.handle("test.message", testMessage);

    assertThat(handled).isTrue();
    assertThat(messageHandler.getLastHandledMessage()).isEqualTo("test.message");
    assertThat(messageHandler.getLastMessageData()).isEqualTo(testMessage);
  }

  @Test
  @DisplayName("Should return false for unregistered message types")
  void shouldReturnFalseForUnregisteredMessageTypes() {
    JsonObject testMessage = new JsonObject().put("data", "test content");

    boolean handled = messageHandler.handle("unknown.message", testMessage);

    assertThat(handled).isFalse();
    assertThat(messageHandler.getLastHandledMessage()).isNull();
  }

  @Test
  @DisplayName("Should get handler function for registered message types")
  void shouldGetHandlerFunctionForRegisteredMessageTypes() {
    Consumer<JsonObject> handler = messageHandler.getHandler("test.message");

    assertThat(handler).isNotNull();

    JsonObject testMessage = new JsonObject().put("test", "value");
    handler.accept(testMessage);

    assertThat(messageHandler.getLastHandledMessage()).isEqualTo("test.message");
    assertThat(messageHandler.getLastMessageData()).isEqualTo(testMessage);
  }

  @Test
  @DisplayName("Should return null for handler of unregistered message types")
  void shouldReturnNullForHandlerOfUnregisteredMessageTypes() {
    Consumer<JsonObject> handler = messageHandler.getHandler("nonexistent.message");

    assertThat(handler).isNull();
  }

  @Test
  @DisplayName("Should handle multiple different message types")
  void shouldHandleMultipleDifferentMessageTypes() {
    JsonObject message1 = new JsonObject().put("type", "first");
    JsonObject message2 = new JsonObject().put("type", "second");

    boolean handled1 = messageHandler.handle("test.message", message1);
    boolean handled2 = messageHandler.handle("another.message", message2);

    assertThat(handled1).isTrue();
    assertThat(handled2).isTrue();
    assertThat(messageHandler.getHandleCount("test.message")).isEqualTo(1);
    assertThat(messageHandler.getHandleCount("another.message")).isEqualTo(1);
  }

  @Test
  @DisplayName("Should handle private methods with annotations")
  void shouldHandlePrivateMethodsWithAnnotations() {
    JsonObject testMessage = new JsonObject().put("private", "data");

    boolean handled = messageHandler.handle("private.handler", testMessage);

    assertThat(handled).isTrue();
    assertThat(messageHandler.getLastHandledMessage()).isEqualTo("private.handler");
  }

  @Test
  @DisplayName("Should handle exceptions in handler methods gracefully")
  void shouldHandleExceptionsInHandlerMethodsGracefully() {
    JsonObject testMessage = new JsonObject().put("trigger", "exception");

    // This should not throw an exception even though the handler method throws
    boolean handled = messageHandler.handle("exception.handler", testMessage);

    assertThat(handled).isTrue();
    assertThat(messageHandler.getExceptionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should handle same message type multiple times")
  void shouldHandleSameMessageTypeMultipleTimes() {
    JsonObject message1 = new JsonObject().put("data", "first");
    JsonObject message2 = new JsonObject().put("data", "second");
    JsonObject message3 = new JsonObject().put("data", "third");

    messageHandler.handle("test.message", message1);
    messageHandler.handle("test.message", message2);
    messageHandler.handle("test.message", message3);

    assertThat(messageHandler.getHandleCount("test.message")).isEqualTo(3);
    assertThat(messageHandler.getLastMessageData()).isEqualTo(message3);
  }

  @Test
  @DisplayName("Should not register methods without annotations")
  void shouldNotRegisterMethodsWithoutAnnotations() {
    String[] registeredTypes = messageHandler.getRegisteredMessageTypes();

    // Should not contain "no.annotation" which is handled by methodWithoutAnnotation()
    assertThat(registeredTypes).doesNotContain("no.annotation");
  }

  /** Test implementation of WebsocketMessageHandler for testing purposes */
  private static class TestableMessageHandler extends WebsocketMessageHandler {

    private String lastHandledMessage;
    private JsonObject lastMessageData;
    private int exceptionCount = 0;
    private final java.util.Map<String, Integer> handleCounts = new java.util.HashMap<>();

    @WebsocketMessage("test.message")
    public void handleTestMessage(JsonObject msg) {
      lastHandledMessage = "test.message";
      lastMessageData = msg;
      handleCounts.merge("test.message", 1, Integer::sum);
    }

    @WebsocketMessage("another.message")
    public void handleAnotherMessage(JsonObject msg) {
      lastHandledMessage = "another.message";
      lastMessageData = msg;
      handleCounts.merge("another.message", 1, Integer::sum);
    }

    @WebsocketMessage("private.handler")
    private void handlePrivateMessage(JsonObject msg) {
      lastHandledMessage = "private.handler";
      lastMessageData = msg;
      handleCounts.merge("private.handler", 1, Integer::sum);
    }

    @WebsocketMessage("exception.handler")
    public void handleExceptionMessage(JsonObject msg) {
      exceptionCount++;
      throw new RuntimeException("Test exception in handler");
    }

    // Method without annotation - should not be registered
    public void methodWithoutAnnotation(JsonObject msg) {
      lastHandledMessage = "no.annotation";
      lastMessageData = msg;
    }

    // Getter methods for testing
    public String getLastHandledMessage() {
      return lastHandledMessage;
    }

    public JsonObject getLastMessageData() {
      return lastMessageData;
    }

    public int getExceptionCount() {
      return exceptionCount;
    }

    public int getHandleCount(String messageType) {
      return handleCounts.getOrDefault(messageType, 0);
    }
  }
}
