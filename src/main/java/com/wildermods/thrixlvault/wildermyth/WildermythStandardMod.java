package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WildermythStandardMod extends WildermythModFile {
	
	private final JsonObject modJson;
	private final transient int schemaVersion;
	private final transient String name;
	private final transient String version;
	private final transient String modid;
	private final transient JsonObject standardModJson;
	private final transient boolean forgeMod;
	
	public WildermythStandardMod(Path path) throws IOException {
		super(path);
		modJson = getModJson(path);
		standardModJson = getStandardModJson(path);
		forgeMod = !modJson.equals(standardModJson);
		
		if(forgeMod) {
			LOGGER.info(STANDARD, path + " (WilderForge)");
			this.schemaVersion = modJson.get("schemaVersion").getAsInt();
			if(schemaVersion != 1) {
				throw new IllegalArgumentException("Can only detect wilderforge mods for fabric mod schema 1. Current Schema: " + schemaVersion);
			}
			JsonElement nameElement = modJson.get("name");
			JsonElement idElement = modJson.get("id");
			JsonElement versionElement = modJson.get("version");
			
			if(nameElement == null) {
				nameElement = idElement;
			}
			
			this.name = nameElement.getAsString();
			this.modid = idElement.getAsString();
			this.version = versionElement.getAsString();
		}
		else {
			LOGGER.info(STANDARD, path);
			schemaVersion = -1; //not a fabric compatible json
			name = modJson.get("name").getAsString();
			version = "0.0.0";
			JsonElement urlElement = modJson.get("url");
			String id = null;
			if(urlElement != null) {
				String url = urlElement.getAsString();
				String prefix = "https://wildermyth.com/wiki/Mods/";
				if(url.startsWith(prefix)) {
					id = url.replace(prefix, "");
				}
			}
			if(id == null) {
				LOGGER.warn(STANDARD, "Could not obtain modid from url in mod.json, falling back to filename");
				id = path.toAbsolutePath().normalize().getFileName().toString();
			}
			
			id = id.toLowerCase();
			id = id.replaceAll("[^a-z0-9_-]", "_");
			if (!Character.isLetter(id.charAt(0)) || id.length() < 2) {
				id = "mod_" + id;
			}
			this.modid = id;
		}
		
		Marker marker = MarkerManager.getMarker(modid);
		
		LOGGER.debug(marker, "name: " + name);
		LOGGER.debug(marker, "version: " + version);
		LOGGER.debug(marker, "hasForgeData: " + forgeMod);
		
		LOGGER.warn(STANDARD, "Mod [" + modid + "] is located at " + path);
		
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String version() {
		return version;
	}

	@Override
	public String modid() {
		return modid;
	}

	@Override
	public JsonObject getModJson() throws IOException {
		return modJson;
	}
	
	public JsonObject getStandardModJson() {
		return standardModJson;
	}

	public boolean isForgeMod() {
		return forgeMod;
	}
	
	public static JsonObject getModJson(Path mod) throws IOException {
		Path wilderForgeJson = mod.resolve("wilderforge.mod.json");
		if(Files.isRegularFile(wilderForgeJson)) {
			return JsonParser.parseReader(new InputStreamReader(Files.newInputStream(wilderForgeJson, StandardOpenOption.READ))).getAsJsonObject();
		}
		return getStandardModJson(mod);
	}
	
	public static JsonObject getStandardModJson(Path mod) throws IOException {
		Path modJson = mod.resolve("mod.json");
		if(Files.isRegularFile(modJson)) {
			return JsonParser.parseReader(new InputStreamReader(Files.newInputStream(modJson, StandardOpenOption.READ))).getAsJsonObject();
		}
		throw new IllegalArgumentException(mod + " is not a wildermyth mod");
	}
	
}
