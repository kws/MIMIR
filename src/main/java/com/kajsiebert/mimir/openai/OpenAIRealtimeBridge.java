package com.kajsiebert.mimir.openai;

import java.util.function.Consumer;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kajsiebert.mimir.openai.rtp.RTPConstants;
import com.kajsiebert.mimir.openai.rtp.RTPSession;
import com.kajsiebert.mimir.openai.rtp.RTPTimerManager;
import com.kajsiebert.mimir.openai.util.ConsumerArray;
import com.kajsiebert.mimir.openai.websocket.WebsocketSession;
import com.kajsiebert.mimir.openai.websocket.WebsocketSessionState;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

public class OpenAIRealtimeBridge implements MediaStreamer {
  private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeBridge.class);

  private static final long SEND_WS_AUDIO_INTERVAL_MS = 250;
  private final Vertx vertx;
  private final WebsocketSession websocketSession;
  private final ConsumerArray<WebsocketSessionState> audioReceivedCallbacks = new ConsumerArray<>();
  private final ConsumerArray<WebsocketSessionState> callEndedCallbacks = new ConsumerArray<>();

  private FlowSpec flowSpec;
  private RTPSession rtpSession;
  private long audioFlushTimerId = -1;
  private RTPTimerManager rtpTimerManager;

  public OpenAIRealtimeBridge(Vertx vertx, ExtensionConfig extensionConfig) {
    this.vertx = vertx;
    this.rtpTimerManager = new RTPTimerManager(vertx);

    websocketSession = new WebsocketSession(vertx, extensionConfig);
    websocketSession.start();
    websocketSession.onAudioReceived(state -> audioReceivedCallbacks.accept(state));
    websocketSession.onCallEnded(state -> callEndedCallbacks.accept(state));
  }
  ;

  public void setFlowSpec(FlowSpec flowSpec) {
    this.flowSpec = flowSpec;
  }

  @Override
  public boolean start() {
    LOG.debug("Starting OpenAIRealtimeBridge");
    if (flowSpec == null) {
      return false;
    }
    if (rtpSession != null) {
      return false;
    }

    rtpSession = new RTPSession(vertx, flowSpec);

    LOG.debug("RTPSession created");

    // Start receiving websocket audio and sending RTP packets using high-priority timer
    rtpTimerManager.startPeriodicTask(
        RTPConstants.PACKET_INTERVAL_MS,
        () -> {
          Buffer data = websocketSession.getNextRtpPacket();
          if (data != null) {
            rtpSession.sendPacket(data);
          }
        });

    // Flush pending audio to websocket
    audioFlushTimerId =
        vertx.setPeriodic(
            SEND_WS_AUDIO_INTERVAL_MS,
            id -> {
              byte[] data = rtpSession.getAudioBuffer();
              if (data.length > 0) {
                websocketSession.sendAudio(data);
              }
            });

    LOG.debug("OpenAIRealtimeBridge started");

    return true;
  }

  @Override
  public boolean halt() {
    LOG.debug("Halting OpenAIRealtimeBridge");
    websocketSession.close();

    if (rtpSession != null) {
      rtpSession.close();
      rtpSession = null;
    }

    // Stop the high-priority RTP timer
    rtpTimerManager.shutdown();

    if (audioFlushTimerId != -1) {
      vertx.cancelTimer(audioFlushTimerId);
      audioFlushTimerId = -1;
    }

    LOG.debug("OpenAIRealtimeBridge halted");
    return true;
  }

  public void onAudioReceived(Consumer<WebsocketSessionState> callback) {
    audioReceivedCallbacks.add(callback);
  }

  public void onCallEnded(Consumer<WebsocketSessionState> callback) {
    callEndedCallbacks.add(callback);
  }
}
