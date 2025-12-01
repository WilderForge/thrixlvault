package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;

public record SkippedDownload(ISteamDownloadable download, Path dest, String skipReason) implements ISteamDownload {

	@Override
	public Path artifactPath() {
		return download.artifactPath();
	}

	@Override
	public long game() {
		return download.game();
	}

	@Override
	public long depot() {
		return download.depot();
	}

	@Override
	public long manifest() {
		return download.manifest();
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

}
