package com.kajsiebert.sip.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.net.IpAddress;
import org.zoolu.net.UdpSocket;

public class UdpSocketContext {
    private static final Logger LOG = LoggerFactory.getLogger(UdpSocketContext.class);
    private final UdpSocket socket;
    private final IpAddress destAddress;
    private final int destPort;
    private final int localPort;
    private final int maxPacketSize;

    public UdpSocketContext(int localPort, String destAddress, int destPort, int maxPacketSize) throws Exception {
        this.localPort = localPort;
        this.destAddress = new IpAddress(destAddress);
        this.destPort = destPort;
        this.maxPacketSize = maxPacketSize;
        this.socket = new UdpSocket(localPort);
        LOG.info("Created UDP socket context: localPort={}, destAddress={}, destPort={}", localPort, destAddress, destPort);
    }

    public UdpSocket getSocket() {
        return socket;
    }

    public IpAddress getDestAddress() {
        return destAddress;
    }

    public int getDestPort() {
        return destPort;
    }

    public int getLocalPort() {
        return localPort;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void close() {
        if (socket != null) {
            try {
                socket.close();
                LOG.info("Closed UDP socket");
            } catch (Exception e) {
                LOG.error("Error closing UDP socket", e);
            }
        }
    }
}
