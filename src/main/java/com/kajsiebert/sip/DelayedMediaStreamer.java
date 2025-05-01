package com.kajsiebert.sip;

import org.mjsip.media.MediaStreamer;
import org.mjsip.media.FlowSpec;
import org.slf4j.LoggerFactory;




/** System that sends back the incoming stream.
  */
public class DelayedMediaStreamer implements MediaStreamer {

	private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DelayedMediaStreamer.class);
	
	/** UdpRelay */
	UdpDelay udp_relay=null;
	

	/** Creates a new media streamer. */
	public DelayedMediaStreamer(FlowSpec flow_spec, long delayMs) {
		try {
			udp_relay=new UdpDelay(flow_spec.getLocalPort(),flow_spec.getRemoteAddress(),flow_spec.getRemotePort(),null);
			LOG.trace("relay {} started with {}ms delay", udp_relay.toString(), delayMs);
		}
		catch (Exception e) {
			LOG.info("Exception.", e);
		}
	}


	/** Starts media streams. */
	@Override
	public boolean start() {
		// do nothing, already started..  
		return true;      
	}


	/** Stops media streams. */
	@Override
	public boolean halt() {
		if (udp_relay!=null) {
			udp_relay.halt();
			udp_relay=null;
			LOG.trace("relay halted");
		}      
		return true;
	}


	// *************************** Callbacks ***************************

	/** From UdpRelayListener. When the remote source address changes. */
	public void onUdpRelaySourceChanged(UdpDelay udp_relay, String remote_src_addr, int remote_src_port) {
		LOG.info("UDP relay: remote address changed: {}:{}", remote_src_addr, remote_src_port);
	}

	/** From UdpRelayListener. When UdpRelay stops relaying UDP datagrams. */
	public void onUdpRelayTerminated(UdpDelay udp_relay) {
		LOG.info("UDP relay: terminated.");
	} 

}
