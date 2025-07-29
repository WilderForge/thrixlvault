package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.DatabaseError;
import com.wildermods.thrixlvault.exception.MissingResourceException;
import com.wildermods.thrixlvault.exception.UnknownVersionException;
import com.wildermods.thrixlvault.steam.INamed;
import com.wildermods.thrixlvault.steam.ISteamDownloadable;
import com.wildermods.thrixlvault.steam.IVersioned;
import com.wildermods.thrixlvault.utils.OS;

public record WildermythManifest(OS os, String version, long manifest) implements ISteamDownloadable, IVersioned, INamed {

	static {
		try {
			init();
		} catch (IntegrityException e) {
			throw new DatabaseError(e);
		}
	}
	
	private static Table<OS, String, Long> manifests;
	private static Multimap<String, Long> branches;
	
	public static final String GAME_NAME = "Wildermyth";
	public static final long GAME_ID = 763890;
	
	@Override
	public long game() {
		return GAME_ID;
	}
	
	@Override
	public long depot() {
		return os.getDepot();
	}
	
	public String version() {
		return version;
	}
	
	public String name() {
		return os() + " " + version();
	}
	
	public boolean isLinux() {
		return os == OS.LINUX;
	}
	
	public boolean isMac() {
		return os == OS.MAC;
	}
	
	public boolean isWindows() {
		return os == OS.WINDOWS;
	}
	
	@Override
	public Path artifactPath() {
		return Path.of(GAME_NAME).resolve(os().name()).resolve(version());
	}
	
	public boolean isPublic() {
		return !branches.values().contains(manifest);
	}
	
	public boolean isUnstable() {
		return isBranch("unstable");
	}
	
	public boolean isGraphicsLib() {
		return isBranch("graphicslib");
	}
	
	public boolean isBranch(String branchName) {
		return branches.get(branchName).contains(manifest);
	}
	
	public boolean equals(Object o) {
		if(o instanceof ISteamDownloadable) {
			return ISteamDownloadable.isEqual(this, (ISteamDownloadable) o);
		}
		return false;
	}
	
	public int hashCode() {
		return ISteamDownloadable.hashCode(this);
	}
	
	public static Set<WildermythManifest> getManifests() {
		return manifestStream().collect(Collectors.toUnmodifiableSet());
	}
	
	public static Stream<WildermythManifest> manifestStream() {
		return manifests.cellSet().stream().map(cell -> new WildermythManifest(cell.getRowKey(), cell.getColumnKey(), cell.getValue()));
	}
	
	@Deprecated
	public static WildermythManifest get(long manifestID) throws UnknownVersionException {
		if(manifests == null) {
			throw new IllegalStateException("Wildermyth Manifests not initialized!");
		}
		Set<Cell<OS, String, Long>> cells = manifests.cellSet().parallelStream()
		.filter((c) -> {return c.getValue().longValue() == manifestID;}) //filter by manifest id
		.sorted((c1, c2) -> c1.getColumnKey().compareTo(c2.getColumnKey())) //sort by version number (in case of manifests with multiple versions)
		.collect(Collectors.toCollection(LinkedHashSet::new)); 
		
		if(!cells.isEmpty()) {
			Cell<OS, String, Long> definition = cells.iterator().next();
			return new WildermythManifest(definition.getRowKey(), definition.getColumnKey(), definition.getValue()); //return the version with the least suffixes (in the case of 1.0r1 and 1.0, 1.0 would be returned)
		}
		throw new UnknownVersionException("Could not find manifest definition " + manifestID + " for any OS");
	}
	
	public static WildermythManifest get(String version) throws UnknownVersionException {
		return get(OS.getOS(), version);
	}
	
	public static WildermythManifest get(OS os, String version) throws UnknownVersionException {
		if(manifests == null) {
			throw new IllegalStateException("Wildermyth Manifests not initialized");
		}
		Long manifestID = manifests.get(os, version);
		if(manifestID == null) {
			throw new UnknownVersionException("Could not find version " + version + " for OS " + os);
		}
		return new WildermythManifest(os, version, manifestID);
	}
	
	private static void init() throws IntegrityException {
		manifests = HashBasedTable.create();
		branches = HashMultimap.create(2, 300);
		InputStream in = WildermythManifest.class.getResourceAsStream("/depots.json");
		if(in == null) {
			throw new MissingResourceException("Could not locate depot json");
		}
		JsonElement element;
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			element = JsonParser.parseReader(reader);
		} catch (IOException e) {
			throw new MissingResourceException("Could not parse depot json", e);
		}
		
		JsonObject o = element.getAsJsonObject();
		
		JsonElement game = o.get("game");
		if(game == null) {
			throw new IntegrityException("No 'game' field in json object");
		}
		if(game.getAsLong() != GAME_ID) {
			throw new IntegrityException("Expected game '" + GAME_ID + "', got " + game + " instead");
		}
		
		JsonElement gameName = o.getAsJsonPrimitive("gameName");
		if(gameName == null) {
			throw new IntegrityException("No 'gameName' field in json object");
		}
		if(!gameName.getAsString().equals("Wildermyth")) {
			throw new IntegrityException("Expected gameName '" + GAME_NAME +"', got " + gameName + " instead");
		}
		
		os:
		for(OS os : OS.values()) {
			long depot;
			
			JsonObject osElement = o.getAsJsonObject(os.name());
			if(osElement == null) {
				throw new IntegrityException("No entry for OS " + os + " in depots.json!");
			}
			
			JsonElement depotElement = osElement.get("depot");
			if(depotElement == null) {
				throw new IntegrityException("No depot defined for OS " + os);
			}
			depot = depotElement.getAsLong();
			System.out.println("Depot is " + depot + ", OS is " + os );
			
			JsonObject manifestsElement = osElement.getAsJsonObject("manifests");
			if(manifestsElement == null) {
				throw new IntegrityException("No manifests defined for OS " + os);
			}
			
			manifests:
			{
				for(Entry<String, JsonElement> entry : manifestsElement.entrySet()) {
					WildermythManifest manifest = new WildermythManifest(os, entry.getKey(), entry.getValue().getAsLong());
					if(manifests.containsValue(manifest.version)) {
						
					}
					WildermythManifest.manifests.put(os, manifest.version(), entry.getValue().getAsLong());
				}
			}
			
			JsonObject branchesElement = osElement.getAsJsonObject("branches");
			if(branchesElement == null) {
				throw new IntegrityException("No 'branches' json object defined for OS " + os);
			}
			branches:
			{
				for(Entry<String, JsonElement> entry : branchesElement.entrySet()) {
					String branch = entry.getKey();
					JsonArray branchArray = entry.getValue().getAsJsonArray();
					for(JsonElement manifest : branchArray) {
						branches.put(branch, manifest.getAsLong());
					}
				}
			}
		}
		
	}
	
}
