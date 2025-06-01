package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;

public record GameManifest(long game, long depot, long manifest) implements IDownloadable {

	public String toString() {
		return game() + " " + depot() + " " + manifest();
	}
	
	@Override
	public boolean equals(Object o) {
		if(o instanceof IDownloadable) {
			return IDownloadable.isEqual(this, (IDownloadable) o);
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return IDownloadable.hashCode(this);
	}

	@Override
	public Path artifactPath() {
		return Path.of(game + "").resolve(depot + "").resolve(manifest + "");
	}
	
}
