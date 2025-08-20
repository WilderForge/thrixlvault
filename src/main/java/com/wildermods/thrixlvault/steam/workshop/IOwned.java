package com.wildermods.thrixlvault.steam.workshop;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;

public interface IOwned {

	public SteamID owner();
	
	public default long ownerID() {
		return SteamNativeHandle.getNativeHandle(owner());
	}
	
}
