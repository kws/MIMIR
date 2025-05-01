package com.kajsiebert.sip;

import org.mjsip.media.FlowSpec.Direction;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.ua.ClientOptions;

/**
 * Implementation of ClientOptions interface for the SIP client.
 * This class represents the configuration options for the SIP client.
 */
public class SipOptions implements ClientOptions {
    private String sipUser;
    private String authUser;
    private String authRealm;
    private String authPasswd;
    private String proxy;
    private String registrar;
    private int audioPort;
    private int videoPort;
    private String mediaAddr = null;
    private boolean noOffer = false;
    private int refuseTime = -1; // never refuse automatically
    private Direction direction = Direction.FULL_DUPLEX;
    private NameAddress userURI;
    private String[] audioCodecs;
    private String transport;
    
    /**
     * Create a new SIP options object based on configuration.
     *
     * @param config The configuration to use
     */
    public SipOptions(Config config) {
        this.sipUser = config.getUsername();
        this.authUser = config.getAuthUsername();
        this.authRealm = config.getDomain();
        this.authPasswd = config.getPassword();
        this.proxy = config.getProxyAddress() + ":" + config.getProxyPort();
        this.registrar = config.getRegistrarAddress() + ":" + config.getRegistrarPort();
        this.audioPort = config.getMediaPort();
        this.videoPort = config.getVideoPort();
        this.audioCodecs = config.getAudioCodecs();
        this.transport = config.getTransport();
        
        // Build the user URI
        SipURI uri = new SipURI(config.getUsername(), config.getDomain());
        this.userURI = new NameAddress(uri);
    }

    @Override
    public NameAddress getUserURI() {
        return userURI;
    }

    @Override
    public String getAuthUser() {
        return authUser;
    }

    @Override
    public String getAuthPasswd() {
        return authPasswd;
    }

    @Override
    public String getAuthRealm() {
        return authRealm;
    }

    @Override
    public String getSipUser() {
        return sipUser;
    }

    @Override
    public String getProxy() {
        return proxy;
    }
    
    /**
     * Get the registrar server address and port.
     *
     * @return The registrar
     */
    public String getRegistrar() {
        return registrar;
    }
    
    /**
     * Set whether to register with the server.
     *
     * @return true if should register, false otherwise
     */
    public boolean mustRegister() {
        return registrar != null && !registrar.isEmpty();
    }

    @Override
    public String getMediaAddr() {
        return mediaAddr;
    }

    @Override
    public boolean getNoOffer() {
        return noOffer;
    }

    @Override
    public int getRefuseTime() {
        return refuseTime;
    }

    @Override
    public Direction getDirection() {
        return direction;
    }
    
    /**
     * Get the audio port.
     *
     * @return The audio port
     */
    public int getAudioPort() {
        return audioPort;
    }
    
    /**
     * Get the video port.
     *
     * @return The video port
     */
    public int getVideoPort() {
        return videoPort;
    }
    
    /**
     * Get the audio codecs.
     *
     * @return The audio codecs
     */
    public String[] getAudioCodecs() {
        return audioCodecs;
    }
    
    /**
     * Get the transport protocol.
     *
     * @return The transport protocol
     */
    public String getTransport() {
        return transport;
    }
}