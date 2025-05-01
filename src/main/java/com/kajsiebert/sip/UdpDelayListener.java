package com.kajsiebert.sip;


/** Listener for UdpRelay.
  */
public interface UdpDelayListener {
	
	/** When the remote source address changes. */
	public void onUdpRelaySourceChanged(UdpDelay udp_relay, String remote_src_addr, int remote_src_port);

	/** When UdpRelay stops relaying UDP datagrams. */
	public void onUdpRelayTerminated(UdpDelay udp_relay);   
}
