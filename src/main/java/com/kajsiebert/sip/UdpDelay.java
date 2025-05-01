/*
 * Copyright (C) 2008 Luca Veltri - University of Parma - Italy
 * 
 * This source code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 * Author(s):
 * Luca Veltri (luca.veltri@unipr.it)
 */

package com.kajsiebert.sip;


import java.io.InterruptedIOException;

import org.zoolu.net.IpAddress;
import org.zoolu.net.UdpPacket;
import org.zoolu.net.UdpSocket;


/** UdpRelay implements a direct UDP datagram relay agent. 
  * It receives UDP packets at a local port and relays them toward a remote UDP socket
  * (destination address/port).
  */
public class UdpDelay extends Thread {
	
	// The maximum IP packet size
	//public static final int MAX_PKT_SIZE=2000;
	public static final int MAX_PKT_SIZE=32000;

	/** Local receiver/sender port */
	int local_port;  
	/** Remote source address */
	IpAddress src_addr;
	/** Remote source port */
	int src_port;  
	/** Destination address */
	IpAddress dest_addr;
	/** Destination port */
	int dest_port;  
	/** Whether it is running */
	boolean stop;
	/** Maximum time that the UDP relay can remain active after been halted (in milliseconds) */
	int socket_to=3000; // 3sec 
	/** Maximum time that the UDP relay remains active without receiving UDP datagrams (in seconds) */
	int alive_to=60; // 1min 
	/** UdpRelay listener */
	UdpDelayListener listener;   

	/** Queue for storing packets */
	private java.util.concurrent.BlockingQueue<DelayedPacket> packetQueue;
	/** Queue processor thread */
	private Thread queueProcessor;
	/** Queue size */
	private static final int QUEUE_SIZE = 100;
	/** Delay in milliseconds */
	private int delayMs = 1000; // Default 1 second delay
	/** Silence packet data */
	private byte[] silenceData;
	/** Socket for sending packets */
	private UdpSocket socket;
	  
	/** Creates a new UDP relay and starts it.
	  * <p> The UdpRelay remains active until method halt() is called. */
	public UdpDelay(int local_port, String dest_addr, int dest_port, UdpDelayListener listener) {
		init(local_port,dest_addr,dest_port,0,listener);
		start();
	}

	/** Creates a new UDP relay and starts it.
	  * <p> The UdpRelay will automatically stop after <i>alive_time</i> seconds
	  *     of idle time (i.e. without receiving UDP datagrams) */
	public UdpDelay(int local_port, String dest_addr, int dest_port, int alive_time, UdpDelayListener listener) {
		init(local_port,dest_addr,dest_port,alive_time,listener);
		start();
	}
	 
	/** Inits a new UDP relay */
	private void init(int local_port, String dest_addr, int dest_port, int alive_time, UdpDelayListener listener) {
		this.local_port=local_port;     
		this.dest_addr=new IpAddress(dest_addr);
		this.dest_port=dest_port;
		this.alive_to=alive_time;
		this.listener=listener;
		src_addr=new IpAddress("0.0.0.0");
		src_port=0;
		stop=false;
		this.packetQueue = new java.util.concurrent.LinkedBlockingQueue<>(QUEUE_SIZE);
		this.silenceData = new byte[MAX_PKT_SIZE];
		java.util.Arrays.fill(silenceData, (byte)0);
	}

	/** Gets the local receiver/sender port */
	public int getLocalPort() {
		return local_port;
	}

	/** Gets the destination address */
	/*public String getDestAddress() {
		return dest_addr;
	}*/

	/** Gets the destination port */
	/*public int getDestPort() {
		return dest_port;
	}*/

	/** Sets a new destination address */
	public UdpDelay setDestAddress(String dest_addr) {
		this.dest_addr=new IpAddress(dest_addr);
		return this;
	}

	/** Sets a new destination port */
	public UdpDelay setDestPort(int dest_port) {
		this.dest_port=dest_port;
		return this;
	}

	/** Whether the UDP relay is running */
	public boolean isRunning() {
		return !stop;
	}

	/** Stops the UDP relay */
	public void halt() {
		stop = true;
		if (queueProcessor != null) {
			queueProcessor.interrupt();
			try {
				queueProcessor.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (socket != null) {
			socket.close();
			socket = null;
		}
	}

	/** Sets the maximum time that the UDP relay can remain active after been halted */
	public void setSoTimeout(int so_to) {
		socket_to=so_to;
	}

	/** Gets the maximum time that the UDP relay can remain active after been halted */
	public int getSoTimeout() {
		return socket_to;
	}

	/** Sets the delay in milliseconds */
	public void setDelay(int delayMs) {
		this.delayMs = delayMs;
	}

	/** Gets the current delay in milliseconds */
	public int getDelay() {
		return delayMs;
	}

	/** Process the queue with delay */
	private void processQueue() {
		while (!stop) {
			try {
				DelayedPacket delayedPacket = packetQueue.take();
				long currentTime = System.currentTimeMillis();
				long waitTime = delayedPacket.timestamp + delayMs - currentTime;
				
				if (waitTime > 0) {
					Thread.sleep(waitTime);
				}
				
				// Send the delayed packet
				UdpPacket sendPacket = new UdpPacket(delayedPacket.data, delayedPacket.data.length);
				sendPacket.setIpAddress(dest_addr);
				sendPacket.setPort(dest_port);
				socket.send(sendPacket);
			} catch (InterruptedException e) {
				if (stop) break;
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void run() {
		try {
			socket = new UdpSocket(local_port);
			byte []buf=new byte[MAX_PKT_SIZE];
			socket.setSoTimeout(socket_to);
			UdpPacket packet=new UdpPacket(buf, buf.length);
			
			long keepalive_to=((1000)*(long)alive_to)-socket_to;
			long expire=System.currentTimeMillis()+keepalive_to;

			// Start the queue processor thread
			queueProcessor = new Thread(this::processQueue);
			queueProcessor.start();

			while(!stop) {
				try {
					socket.receive(packet);           
				}
				catch (InterruptedIOException ie) {
					if (alive_to>0 && System.currentTimeMillis()>expire) halt();
					continue;
				}
				
				if (src_port!=packet.getPort() || !src_addr.equals(packet.getIpAddress())) {
					src_port=packet.getPort();
					src_addr=packet.getIpAddress();
					if (listener!=null) listener.onUdpRelaySourceChanged(this,src_addr.toString(),src_port);
				}

				// Copy packet data and add to queue with timestamp
				byte[] packetData = new byte[packet.getLength()];
				System.arraycopy(packet.getData(), 0, packetData, 0, packet.getLength());
				try {
					packetQueue.put(new DelayedPacket(packetData, System.currentTimeMillis()));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}

				packet=new UdpPacket(buf, buf.length);
				expire=System.currentTimeMillis()+keepalive_to;
			}
			socket.close();
			if (listener!=null) listener.onUdpRelayTerminated(this);
		}
		catch (Exception e) { e.printStackTrace(); } 
	}

	/** Class to hold packet data and timestamp */
	private static class DelayedPacket {
		final byte[] data;
		final long timestamp;
		
		DelayedPacket(byte[] data, long timestamp) {
			this.data = data;
			this.timestamp = timestamp;
		}
	}
	
	/** Gets a String representation of the Object */
	@Override
	public String toString() {
		return "localhost:"+Integer.toString(local_port)+"-->"+dest_addr+":"+dest_port;
	}

	// ********************************** MAIN *********************************

	/** The main method. */
	public static void main(String[] args) {
		
		if (args.length<3) {
			
			System.out.println("usage:\n   java UdpRelay <local_port> <address> <port> [ <alive_time> ]");
			System.exit(0);
		}
		
		int local_port=Integer.parseInt(args[0]);
		int remote_port=Integer.parseInt(args[2]);
		String remote_address=args[1];
		
		int alive_time=0;
		if (args.length>3) alive_time=Integer.parseInt(args[3]);
		
		new UdpDelay(local_port,remote_address,remote_port,alive_time,null);
	}

}
 
