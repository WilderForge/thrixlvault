package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;

public record CompletedDownload(ISteamDownloadable download, Path dest) implements ISteamDownload {

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
	public String version() {
		return download.version();
	}

	@Override
	public Path artifactPath() {
		return download.artifactPath();
	}
	
	@Override
	public String name() {
		return download.name();
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
