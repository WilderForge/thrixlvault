package com.wildermods.thrixlvault.steam;

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
	
}
