package com.wildermods.thrixlvault.wildermyth.graph;

import java.nio.file.Path;
import java.util.LinkedHashSet;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.wildermods.thrixlvault.wildermyth.StandardModUtils;
import com.wildermods.thrixlvault.wildermyth.WildermythMod;

public class AssetNode {

	private static final Logger LOGGER = StandardModUtils.LOGGER;
	
	/**
	 * The original mod that defined this asset
	 */
	private final WildermythMod originalMod;
	
	public final Path asset;
	
	private final LinkedHashSet<WildermythMod> definingMods = new LinkedHashSet<>();
	private final LinkedHashSet<AssetNode> injections = new LinkedHashSet<>();
	private final Marker marker;
	
	public AssetNode(WildermythMod mod, Path asset) {
		this.originalMod = mod;
		this.asset = asset;
		this.marker = MarkerManager.getMarker(mod.modid());
		LOGGER.trace(marker, "defines " + asset);
		definingMods.add(mod);
	}
	
	public void addOverwrite(WildermythMod mod) {
		LOGGER.info(marker, "overwrites " + mod + " file " + asset);
		definingMods.add(mod);
	}
	
	public void addInjection(AssetNode injection) {
		injections.add(injection);
		LOGGER.info(marker, "Injects " + asset);
	}
	
}
