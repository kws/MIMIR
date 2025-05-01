/*
 * Copyright (C) 2005 Luca Veltri - University of Parma - Italy
 * 
 * This file is part of MjSip (http://www.mjsip.org)
 * 
 * MjSip is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * MjSip is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with MjSip; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 * Author(s):
 * Luca Veltri (luca.veltri@unipr.it)
 */
package org.mjsip.sip.header;

/** Header is the base Class for all SIP Headers
 */
public abstract class Header {
	
	/** The header type */
	private String _name;

	/** Creates a new Header. */
	public Header(String hname) {
		_name = hname;
	}

	/** Creates a new Header. */
	public Header(Header hd) {
		_name = hd.getName();
	}

	/** Whether the Header is equal to Object <i>obj</i> */
	@Override
	public final boolean equals(Object obj) {
		try {
			Header hd=(Header)obj;
			if (hd.getName().equals(this.getName()) && hd.getValue().equals(this.getValue())) return true;
			else return false;
		}
		catch (Exception e) {  return false;  }
	}

	@Override
	public int hashCode() {
		return getName().hashCode() + getValue().hashCode();
	}

	/** Gets name of Header */
	public final String getName() {
		return _name;
	}

	/** Gets value of Header */
	public abstract String getValue();

	/** Gets string representation of Header */
	@Override
	public final String toString() {
		return getName() + ": " + getValue() + "\r\n";
	}
}
