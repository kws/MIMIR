package com.kajsiebert.sip.openai.websocket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods as message handlers for specific message types.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WebsocketMessage {
    /**
     * The message type this handler should process.
     */
    String value();
} 