package com.kajsiebert.sip.openai.rtp;

import org.mjsip.media.FlowSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;

public class RTPSession {
  private static final Logger LOG = LoggerFactory.getLogger(RTPSession.class);

  private final FlowSpec flowSpec;
  private final DatagramSocket udpSocket;
  final RTPAudioBuffer audioBuffer = new RTPAudioBuffer();

  public RTPSession(Vertx vertx, FlowSpec flowSpec) {
    this.flowSpec = flowSpec;

    DatagramSocketOptions options = new DatagramSocketOptions();

    udpSocket = vertx.createDatagramSocket(options);

    udpSocket.listen(
        flowSpec.getLocalPort(),
        "0.0.0.0",
        ar -> {
          if (ar.succeeded()) {
            LOG.info("Vert.x UDP listening on port {}", flowSpec.getLocalPort());
            udpSocket.handler(
                packet -> {
                  audioBuffer.appendPacket(packet.data());
                });
          } else {
            LOG.error("Failed to bind UDP socket on {}", flowSpec.getLocalPort(), ar.cause());
          }
        });
  }

  public void close() {
    udpSocket.close();
  }

  public byte[] getAudioBuffer() {
    return audioBuffer.getAudioBuffer();
  }

  public void sendPacket(Buffer data) {
    udpSocket.send(data, flowSpec.getRemotePort(), flowSpec.getRemoteAddress(), snd -> {});
  }
}
