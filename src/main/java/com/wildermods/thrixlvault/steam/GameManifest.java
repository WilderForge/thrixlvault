package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;

public record GameManifest(long game, long depot, long manifest) implements ISteamDownloadable {

	public String toString() {
		return game() + " " + depot() + " " + manifest();
	}
	
	@Override
	public boolean equals(Object o) {
		if(o instanceof ISteamDownloadable) {
			return ISteamDownloadable.isEqual(this, (ISteamDownloadable) o);
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return ISteamDownloadable.hashCode(this);
	}

	@Override
	public Path artifactPath() {
		return Path.of(game + "").resolve(depot + "").resolve(manifest + "");
	}
	
}
