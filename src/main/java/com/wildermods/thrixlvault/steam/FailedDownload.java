package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;

public record FailedDownload(ISteamDownloadable download, Path dest, Throwable failReason) implements ISteamDownload {

	@Override
	public long game() {
		return download.game();
	}

	@Override
	public long manifest() {
		return download.manifest();
	}

	@Override
	public long depot() {
		return download.depot();
	}
	
	@Override
	public Path artifactPath() {
		return download.artifactPath();
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
