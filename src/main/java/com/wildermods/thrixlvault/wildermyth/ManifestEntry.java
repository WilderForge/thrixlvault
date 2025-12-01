package com.wildermods.thrixlvault.wildermyth;

import com.wildermods.thrixlvault.steam.IManifest;
import com.wildermods.thrixlvault.steam.IVersioned;

record ManifestEntry(String version, long manifest) implements IManifest, IVersioned {

	public boolean isKnown() {
		return !version.startsWith("unkn_");
	}
	
}
