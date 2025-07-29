package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.JsonObject;
import com.wildermods.thrixlvault.steam.INamed;
import com.wildermods.thrixlvault.steam.IVaultable;
import com.wildermods.thrixlvault.steam.IVersioned;

public interface WildermythMod extends INamed, IVersioned, IVaultable {

	public static final Logger LOGGER = LogManager.getLogger("ModParser");
	public static final Marker STANDARD = MarkerManager.getMarker("STANDARD");
	public static final Marker CORE = MarkerManager.getMarker("CORE");
	
	public String modid();
	
	public Path getModFile();
	
	public JsonObject getModJson() throws IOException;
	
	public static WildermythMod get(Path mod) throws IOException {
		if(Files.isRegularFile(mod)) {
			if(mod.getFileName().toString().endsWith(".jar")) {
				return new WildermythCoremod(mod);
			}
		}
		else if(Files.isDirectory(mod)) {
			return new WildermythStandardMod(mod);
		}
		
		return null;
	}
	
}
