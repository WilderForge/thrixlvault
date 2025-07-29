package com.wildermods.thrixlvault.steam;

public interface IDownloadable extends INamed, IVaultable, IVersioned {
	
	public default String version() {
		return "0.0.0";
	}
	
}
