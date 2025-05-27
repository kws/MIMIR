package com.kajsiebert.sip.openai;

import java.util.function.Consumer;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;

import com.kajsiebert.sip.openai.rtp.RTPConstants;
import com.kajsiebert.sip.openai.rtp.RTPSession;
import com.kajsiebert.sip.openai.util.ConsumerArray;
import com.kajsiebert.sip.openai.websocket.WebsocketSession;
import com.kajsiebert.sip.openai.websocket.WebsocketSessionState;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

public class OpenAIRealtimeBridge implements MediaStreamer {

    private static final long SEND_WS_AUDIO_INTERVAL_MS = 350;
    private final Vertx vertx;
    private final WebsocketSession websocketSession;
    private final ConsumerArray<WebsocketSessionState> audioReceivedCallbacks = new ConsumerArray<>();

    private FlowSpec flowSpec;
    private RTPSession rtpSession;
    private long periodicTimerId = -1;
    private long audioFlushTimerId = -1;

    public OpenAIRealtimeBridge(Vertx vertx, ExtensionConfig extensionConfig) {
        this.vertx = vertx;

        websocketSession = new WebsocketSession(vertx, extensionConfig);
        websocketSession.start();
        websocketSession.onAudioReceived(state -> audioReceivedCallbacks.accept(state));
    };

    public void setFlowSpec(FlowSpec flowSpec) {
        this.flowSpec = flowSpec;
    }

    @Override
    public boolean start() {
        if (flowSpec == null) {
            return false;
        }
        if (rtpSession != null) {
            return false;
        }

        rtpSession = new RTPSession(vertx, flowSpec);

        // Start receiving websocket audio and sending RTP packets
        periodicTimerId = vertx.setPeriodic(RTPConstants.PACKET_INTERVAL_MS, id -> {
            Buffer data = websocketSession.getNextRtpPacket();
            if (data != null) {
                rtpSession.sendPacket(data);
            }
        });

        // Flush pending audio to websocket
        audioFlushTimerId = vertx.setPeriodic(SEND_WS_AUDIO_INTERVAL_MS, id -> {
            byte[] data = rtpSession.getAudioBuffer();
            if (data.length > 0) {
                websocketSession.sendAudio(data);
            }
        });

        return true;
    }

    @Override
    public boolean halt() {
        websocketSession.close();

        if (rtpSession != null) {
            rtpSession.close();
            rtpSession = null;
        }

        if (periodicTimerId != -1) {
            vertx.cancelTimer(periodicTimerId);
            periodicTimerId = -1;
        }

        if (audioFlushTimerId != -1) {
            vertx.cancelTimer(audioFlushTimerId);
            audioFlushTimerId = -1;
        }

        return true;
    }

    public void onAudioReceived(Consumer<WebsocketSessionState> callback) {
        audioReceivedCallbacks.add(callback);
    }

}
