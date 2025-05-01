package com.sip.client;

/**
 * Configuration for the SIP client.
 * This class holds all the configuration parameters for the SIP client,
 * including SIP account details, server addresses, and media settings.
 */
public class Config {
    // SIP account settings
    private String username = "";
    private String password = "";
    private String authUsername = null;
    private String domain = "asterisk.local";
    
    // Server addresses
    private String registrarAddress = "asterisk.local";
    private int registrarPort = 5060;
    private String proxyAddress = "asterisk.local";
    private int proxyPort = 5060;
    
    // Local settings
    private String localAddress = "0.0.0.0";
    private int localPort = 5060;
    
    // Media settings
    private int mediaPort = 4000;
    private int videoPort = 4002;
    private String[] audioCodecs = {"PCMU", "PCMA", "G722", "speex"};
    private String transport = "udp";
    
    // Registration settings
    private int registerExpires = 3600;
    
    /**
     * Create a new configuration with default values.
     */
    public Config() {
        // Default constructor with default values
    }
    
    /**
     * Get the SIP username.
     * 
     * @return The SIP username
     */
    public String getUsername() {
        return username;
    }
    
    /**
     * Set the SIP username.
     * 
     * @param username The SIP username
     */
    public void setUsername(String username) {
        this.username = username;
    }
    
    /**
     * Get the SIP password.
     * 
     * @return The SIP password
     */
    public String getPassword() {
        return password;
    }
    
    /**
     * Set the SIP password.
     * 
     * @param password The SIP password
     */
    public void setPassword(String password) {
        this.password = password;
    }
    
    /**
     * Get the authentication username.
     * 
     * @return The authentication username
     */
    public String getAuthUsername() {
        return authUsername != null ? authUsername : username;
    }
    
    /**
     * Set the authentication username.
     * 
     * @param authUsername The authentication username
     */
    public void setAuthUsername(String authUsername) {
        this.authUsername = authUsername;
    }
    
    /**
     * Get the SIP domain/realm.
     * 
     * @return The SIP domain/realm
     */
    public String getDomain() {
        return domain;
    }
    
    /**
     * Set the SIP domain/realm.
     * 
     * @param domain The SIP domain/realm
     */
    public void setDomain(String domain) {
        this.domain = domain;
    }
    
    /**
     * Get the registrar address.
     * 
     * @return The registrar address
     */
    public String getRegistrarAddress() {
        return registrarAddress;
    }
    
    /**
     * Set the registrar address.
     * 
     * @param registrarAddress The registrar address
     */
    public void setRegistrarAddress(String registrarAddress) {
        this.registrarAddress = registrarAddress;
    }
    
    /**
     * Get the registrar port.
     * 
     * @return The registrar port
     */
    public int getRegistrarPort() {
        return registrarPort;
    }
    
    /**
     * Set the registrar port.
     * 
     * @param registrarPort The registrar port
     */
    public void setRegistrarPort(int registrarPort) {
        this.registrarPort = registrarPort;
    }
    
    /**
     * Get the proxy address.
     * 
     * @return The proxy address
     */
    public String getProxyAddress() {
        return proxyAddress;
    }
    
    /**
     * Set the proxy address.
     * 
     * @param proxyAddress The proxy address
     */
    public void setProxyAddress(String proxyAddress) {
        this.proxyAddress = proxyAddress;
    }
    
    /**
     * Get the proxy port.
     * 
     * @return The proxy port
     */
    public int getProxyPort() {
        return proxyPort;
    }
    
    /**
     * Set the proxy port.
     * 
     * @param proxyPort The proxy port
     */
    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }
    
    /**
     * Get the local address.
     * 
     * @return The local address
     */
    public String getLocalAddress() {
        return localAddress;
    }
    
    /**
     * Set the local address.
     * 
     * @param localAddress The local address
     */
    public void setLocalAddress(String localAddress) {
        this.localAddress = localAddress;
    }
    
    /**
     * Get the local port.
     * 
     * @return The local port
     */
    public int getLocalPort() {
        return localPort;
    }
    
    /**
     * Set the local port.
     * 
     * @param localPort The local port
     */
    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }
    
    /**
     * Get the media port.
     * 
     * @return The media port
     */
    public int getMediaPort() {
        return mediaPort;
    }
    
    /**
     * Set the media port.
     * 
     * @param mediaPort The media port
     */
    public void setMediaPort(int mediaPort) {
        this.mediaPort = mediaPort;
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
     * Set the video port.
     * 
     * @param videoPort The video port
     */
    public void setVideoPort(int videoPort) {
        this.videoPort = videoPort;
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
     * Set the audio codecs.
     * 
     * @param audioCodecs The audio codecs
     */
    public void setAudioCodecs(String[] audioCodecs) {
        this.audioCodecs = audioCodecs;
    }
    
    /**
     * Get the transport protocol.
     * 
     * @return The transport protocol
     */
    public String getTransport() {
        return transport;
    }
    
    /**
     * Set the transport protocol.
     * 
     * @param transport The transport protocol
     */
    public void setTransport(String transport) {
        this.transport = transport;
    }
    
    /**
     * Get the registration expiration time in seconds.
     * 
     * @return The registration expiration time
     */
    public int getRegisterExpires() {
        return registerExpires;
    }
    
    /**
     * Set the registration expiration time in seconds.
     * 
     * @param registerExpires The registration expiration time
     */
    public void setRegisterExpires(int registerExpires) {
        this.registerExpires = registerExpires;
    }
}
