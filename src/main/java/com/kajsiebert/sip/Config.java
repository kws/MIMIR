package com.kajsiebert.sip;

/**
 * Configuration class for the SIP client.
 * This class holds all configuration parameters for the SIP client.
 */
public class Config {
    // SIP Parameters
    private String username = "";
    private String password = "";
    private String domain = "";
    private String authUsername = null;
    private String localAddress = "0.0.0.0";
    private int localPort = 5060;
    
    // Server Parameters
    private String proxyAddress = "127.0.0.1";
    private int proxyPort = 5060;
    private String registrarAddress = "127.0.0.1";
    private int registrarPort = 5060;
    private int registerExpires = 3600;
    
    // Media Parameters
    private int mediaPort = 4000;
    private int videoPort = 4002;
    private String[] audioCodecs = {"PCMU", "PCMA", "G722", "GSM"};
    private String transport = "udp";

    /**
     * Create a new configuration with default values.
     */
    public Config() {
        // Default constructor
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
     * Get the SIP domain.
     * 
     * @return The SIP domain
     */
    public String getDomain() {
        return domain;
    }
    
    /**
     * Set the SIP domain.
     * 
     * @param domain The SIP domain
     */
    public void setDomain(String domain) {
        this.domain = domain;
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
     * Get the local IP address to bind to.
     * 
     * @return The local IP address
     */
    public String getLocalAddress() {
        return localAddress;
    }
    
    /**
     * Set the local IP address to bind to.
     * 
     * @param localAddress The local IP address
     */
    public void setLocalAddress(String localAddress) {
        this.localAddress = localAddress;
    }
    
    /**
     * Get the local port to bind to.
     * 
     * @return The local port
     */
    public int getLocalPort() {
        return localPort;
    }
    
    /**
     * Set the local port to bind to.
     * 
     * @param localPort The local port
     */
    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }
    
    /**
     * Get the SIP proxy address.
     * 
     * @return The proxy address
     */
    public String getProxyAddress() {
        return proxyAddress;
    }
    
    /**
     * Set the SIP proxy address.
     * 
     * @param proxyAddress The proxy address
     */
    public void setProxyAddress(String proxyAddress) {
        this.proxyAddress = proxyAddress;
    }
    
    /**
     * Get the SIP proxy port.
     * 
     * @return The proxy port
     */
    public int getProxyPort() {
        return proxyPort;
    }
    
    /**
     * Set the SIP proxy port.
     * 
     * @param proxyPort The proxy port
     */
    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }
    
    /**
     * Get the SIP registrar address.
     * 
     * @return The registrar address
     */
    public String getRegistrarAddress() {
        return registrarAddress;
    }
    
    /**
     * Set the SIP registrar address.
     * 
     * @param registrarAddress The registrar address
     */
    public void setRegistrarAddress(String registrarAddress) {
        this.registrarAddress = registrarAddress;
    }
    
    /**
     * Get the SIP registrar port.
     * 
     * @return The registrar port
     */
    public int getRegistrarPort() {
        return registrarPort;
    }
    
    /**
     * Set the SIP registrar port.
     * 
     * @param registrarPort The registrar port
     */
    public void setRegistrarPort(int registrarPort) {
        this.registrarPort = registrarPort;
    }
    
    /**
     * Get the registration expiration time.
     * 
     * @return The registration expiration time in seconds
     */
    public int getRegisterExpires() {
        return registerExpires;
    }
    
    /**
     * Set the registration expiration time.
     * 
     * @param registerExpires The registration expiration time in seconds
     */
    public void setRegisterExpires(int registerExpires) {
        this.registerExpires = registerExpires;
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
}