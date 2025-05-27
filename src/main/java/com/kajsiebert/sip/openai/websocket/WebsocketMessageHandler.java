package com.kajsiebert.sip.openai.websocket;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base class that provides message handling capabilities using reflection
 * to find methods annotated with @Message.
 */
public abstract class WebsocketMessageHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebsocketMessageHandler.class);
    
    private final Map<String, Method> handlers = new HashMap<>();
    
    protected WebsocketMessageHandler() {
        initializeHandlers();
    }
    
    /**
     * Scans the class for methods annotated with @Message and builds a lookup map.
     */
    private void initializeHandlers() {
        Class<?> clazz = this.getClass();
        Method[] methods = clazz.getDeclaredMethods();
        
        for (Method method : methods) {
            WebsocketMessage messageAnnotation = method.getAnnotation(WebsocketMessage.class);
            if (messageAnnotation != null) {
                String messageType = messageAnnotation.value();
                method.setAccessible(true); // Allow calling private/protected methods
                handlers.put(messageType, method);
                LOG.debug("Registered handler for message type: {}", messageType);
            }
        }
    }
    
    /**
     * Gets a handler function for the specified message type.
     * 
     * @param messageType the message type to find a handler for
     * @return a Consumer that will invoke the handler method, or null if no handler found
     */
    public Consumer<JsonObject> getHandler(String messageType) {
        Method method = handlers.get(messageType);
        if (method == null) {
            return null;
        }
        
        return (msg) -> {
            try {
                method.invoke(this, msg);
            } catch (Exception e) {
                LOG.error("Error invoking handler for message type: {}", messageType, e);
            }
        };
    }
    
    /**
     * Handles a message by finding and invoking the appropriate handler method.
     * 
     * @param messageType the type of message to handle
     * @param msg the message data
     * @return true if a handler was found and invoked, false otherwise
     */
    public boolean handle(String messageType, JsonObject msg) {
        Consumer<JsonObject> handler = getHandler(messageType);
        if (handler != null) {
            handler.accept(msg);
            return true;
        } else {
            LOG.warn("No handler found for message type: {}", messageType);
            return false;
        }
    }
    
    /**
     * Returns all registered message types.
     */
    public String[] getRegisteredMessageTypes() {
        return handlers.keySet().toArray(new String[0]);
    }
} 