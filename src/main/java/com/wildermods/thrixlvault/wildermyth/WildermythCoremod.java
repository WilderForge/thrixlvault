package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WildermythCoremod extends WildermythModFile {
	
	private final JsonObject modJson;
	private final transient int schemaVersion;
	private final transient String name;
	private final transient String version;
	private final transient String modid;
	
	public WildermythCoremod(Path jar) throws IOException {
		super(jar);
		LOGGER.info(CORE, path);
		this.modJson = getModJson();
		this.schemaVersion = modJson.get("schemaVersion").getAsInt();
		if(schemaVersion != 1) {
			throw new IllegalArgumentException("Can only detect coremods for fabric mod schema 1. Current Schema: " + schemaVersion);
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
		
		Marker marker = MarkerManager.getMarker(modid);
		
		LOGGER.debug(marker, "name: " + name);
		LOGGER.debug(marker, "version: " + version);
		
		LOGGER.warn(CORE, modid + " is " + path);
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

	public JsonObject getModJson() throws IOException {
		return getModJson(getModFile());
	}
	
	public static JsonObject getModJson(Path jar) throws IOException {
		try(ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if(entry != null) {
				try (InputStream is = zip.getInputStream(entry)) {
					return JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
				}
			}
		}
		throw new IllegalArgumentException(jar + " is not fabric mod");
	}
	
}
