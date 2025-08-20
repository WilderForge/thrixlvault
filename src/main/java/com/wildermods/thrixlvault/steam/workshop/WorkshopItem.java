package com.wildermods.thrixlvault.steam.workshop;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamUGCDetails;
import com.wildermods.thrixlvault.steam.IManifest;

public class WorkshopItem implements IManifest, IOwned {

	public SteamUGCDetails details;

	@Override
	public long manifest() {
		return SteamNativeHandle.getNativeHandle(details.getPublishedFileID());
	}

	@Override
	public SteamID owner() {
		return details.getOwnerID();
	}
	
}
