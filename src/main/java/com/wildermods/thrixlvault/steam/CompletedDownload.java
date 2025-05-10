package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;

public record CompletedDownload(IDownloadable download, Path dest) implements IDownload {

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
	public String name() {
		return download.name();
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
