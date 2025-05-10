package com.wildermods.thrixlvault.utils;

import org.apache.commons.lang3.SystemUtils;

import com.wildermods.thrixlvault.steam.IDepot;

public enum OS {
	WINDOWS(763891),
	MAC(763892),
	LINUX(763893);

	private final long depot;
	
	private OS(int depot) {
		this.depot = depot;
	}
	
	public long getDepot() {
		return depot;
	}
	
	private static final OS os = getOS();
	
	public static OS fromDepot(IDepot depot) {
		for(OS os : values()) {
			if(os.depot == depot.depot()) {
				return os;
			}
		}
		throw new IllegalArgumentException(depot.depot() + "");
	}
	
	public static OS getOS() {
		if(SystemUtils.IS_OS_LINUX) {
			return LINUX;
		}
		if(SystemUtils.IS_OS_WINDOWS) {
			return WINDOWS;
		}
		if(SystemUtils.IS_OS_MAC) {
			return MAC;
		}
		throw new UnsupportedOperationException();
	}

}
