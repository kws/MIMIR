package com.kajsiebert.sip.openai.websocket;

public enum WebsocketSessionState {
    NEW,
    CONNECTED,
    SESSION_CREATED,
    ANSWERED,
    AUDIO_RECEIVED,
    TERMINATED,
} 