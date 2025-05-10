package com.wildermods.thrixlvault.steam;

@FunctionalInterface
public interface IManifest extends IVersioned {
	
	public long manifest();
	
	public default String version() {
		return manifest() + "";
	}
	
}
