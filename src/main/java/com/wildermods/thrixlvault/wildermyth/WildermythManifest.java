package com.wildermods.thrixlvault.wildermyth;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.DatabaseError;
import com.wildermods.thrixlvault.exception.MissingResourceException;
import com.wildermods.thrixlvault.exception.UnknownVersionException;
import com.wildermods.thrixlvault.exception.VersionParsingException;
import com.wildermods.thrixlvault.steam.INamed;
import com.wildermods.thrixlvault.steam.ISteamDownloadable;
import com.wildermods.thrixlvault.steam.IVersioned;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.utils.version.Version;

public record WildermythManifest(OS os, String version, long manifest) implements ISteamDownloadable, IVersioned, INamed, Comparable<IVersioned> {

	static {
		try {
			init();
		} catch (IntegrityException e) {
			throw new DatabaseError(e);
		}
	}
	
	private static Map<OS, Multimap<String, ManifestEntry>> manifests;
	
	public static final String GAME_NAME = "Wildermyth";
	public static final long GAME_ID = 763890;
	
	@Override
	public long game() {
		return GAME_ID;
	}
	
	@Override
	public String gameName() {
		return GAME_NAME;
	}
	
	@Override
	public long depot() {
		return os.getDepot();
	}
	
	public String version() {
		return version;
	}
	
	@Override
	public Version asVersion() {
		try {
			return Version.parse(fixVersion(version()));
		}
		catch(VersionParsingException e) {
			throw new AssertionError(e);
		}
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
	
	public boolean isCurrentOS() {
		return os == OS.getOS();
	}
	
	public boolean isVersionKnown() {
		return !version.startsWith("unkn_");
	}
	
	public boolean isLatest() {
		return getAllLatest("public", "unstable").contains(this);
	}
	
	public boolean isLatestStable() {
		return isLatest("public");
	}
	
	public boolean isLatest(String branch) {
		return getAllLatest(branch).contains(this);
	}
	
	@Override
	public Path artifactPath() {
		return Path.of(GAME_NAME).resolve(os().name()).resolve(version());
	}
	
	public boolean isPublic() {
		return isBranch("public");
	}
	
	public boolean isUnstable() {
		return isBranch("unstable");
	}
	
	public boolean isGraphicsLib() {
		return isBranch("graphicslib");
	}
	
	public boolean isBranch(String branchName) {
		Multimap<String, ManifestEntry> mmap = manifests.get(os());
		if (mmap == null) return false;
		Collection<ManifestEntry> entries = mmap.get(branchName);
		if (entries == null) return false;
		return entries.stream().anyMatch(e -> e.manifest() == manifest());
	}
	
	@Override
	public String downloadBlockedReason() {
		if(!isPublic() && !isUnstable()) {
			return "Only manifests from the 'public' or 'unstable' branch can be downloaded.";
		}
		if(isUnstable()) {
			WildermythManifest latestUnstable = getLatest("unstable");
			if(!isLatest("unstable")) {
				return "Can only download the latest unstable version: (" + latestUnstable.version + "). Cannot download (" + version() + ")";
			}
		}
		return null;
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
	
	public static Set<WildermythManifest> getManifests(OS os) {
		return manifestStream(os).collect(Collectors.toUnmodifiableSet());
	}
	
	public static Stream<WildermythManifest> manifestStream() {
		return manifests.entrySet().stream()
			.flatMap(e -> e.getValue().entries().stream()
				.map(x -> new WildermythManifest(e.getKey(), x.getValue().version(), x.getValue().manifest())));
	}
	
	public static Stream<WildermythManifest> manifestStream(OS os) {
		Multimap<String, ManifestEntry> mmap = manifests.get(os);
		if(mmap == null) {
			return Stream.empty();
		}
		
		return mmap.entries().stream().map(e -> new WildermythManifest(os, e.getValue().version(), e.getValue().manifest()));
	}
	
	@Deprecated
	public static WildermythManifest get(long manifestID) throws UnknownVersionException {
		return manifestStream()
				.filter(m -> m.manifest() == manifestID)
				.findFirst()
				.orElseThrow(() ->
					new UnknownVersionException("Could not find manifest definition " + manifestID + " for any OS"));
	}
	
	public static WildermythManifest get(String version) throws UnknownVersionException, VersionParsingException {
		return get(OS.getOS(), version);
	}
	
	public static WildermythManifest get(OS os, String version) throws UnknownVersionException, VersionParsingException {
		return get(os, Version.parse(fixVersion(version)));
	}
	
	public static WildermythManifest get(OS os, Version version) throws UnknownVersionException {
		return manifestStream(os)
				.filter(m -> m.asVersion().equals(version))
				.findFirst()
				.orElseThrow(() -> new UnknownVersionException("Could not find manifest " + version));
	}
	
	public static WildermythManifest getLatest() {
		return getLatest(OS.getOS());
	}
	
	public static WildermythManifest getLatest(String... branches) {
		return getLatest(OS.getOS(), branches);
	}
	
	public static WildermythManifest getLatest(OS os, String... branchNames) {
		if (branchNames == null || branchNames.length == 0) {
			branchNames = new String[]{"public"};
		}

		Multimap<String, ManifestEntry> mmap = manifests.get(os);
		if (mmap == null || mmap.isEmpty()) {
			return null;
		}

		Set<String> branches = Set.of(branchNames);

		ManifestEntry bestEntry = null;
		Version bestVersion = null;

		for (String branch : branches) {
			Collection<ManifestEntry> entries = mmap.get(branch);
			if (entries == null || entries.isEmpty()) {
				continue;
			}

			for (ManifestEntry entry : entries) {
				Version v;
				try {
					if(entry.isKnown()) {
						v = Version.parse(fixVersion(entry.version()));
					}
					else {
						continue;
					}
				} catch (VersionParsingException ex) {
					continue;
				}

				if (bestVersion == null || v.compareTo(bestVersion) > 0) {
					bestVersion = v;
					bestEntry = entry;
				}
			}
		}

		if (bestEntry == null) {
			return null;
		}

		return new WildermythManifest(os, bestEntry.version(), bestEntry.manifest());
	}
	
	public static Set<WildermythManifest> getAllLatest(String... branchNames) {
		if (branchNames == null || branchNames.length == 0) {
			branchNames = new String[]{"public"};
		}

		Set<String> branches = Set.of(branchNames);
		Set<WildermythManifest> result = new HashSet<>();

		for (OS os : OS.values()) {
			Multimap<String, ManifestEntry> mmap = manifests.get(os);
			if (mmap == null || mmap.isEmpty()) {
				continue;
			}

			for (String branch : branches) {
				Collection<ManifestEntry> entries = mmap.get(branch);
				if (entries == null || entries.isEmpty()) {
					continue;
				}

				ManifestEntry bestEntry = null;
				Version bestVersion = null;

				for (ManifestEntry entry : entries) {

					if (!entry.isKnown()) {
						continue;
					}

					Version v;
					try {
						v = Version.parse(fixVersion(entry.version()));
					} catch (VersionParsingException e) {
						continue;
					}

					if (bestVersion == null || v.compareTo(bestVersion) > 0) {
						bestVersion = v;
						bestEntry = entry;
					}
				}

				if (bestEntry != null) {
					result.add(new WildermythManifest(os, bestEntry.version(), bestEntry.manifest()));
				}
			}
		}

		return Collections.unmodifiableSet(result);
	}
	
	private static String fixVersion(String vanillaVersion) {
		return vanillaVersion.replace('+', '.').replace("r", "-r.");
	}
	
	private static void init() throws IntegrityException {
		manifests = new HashMap<>();
		InputStream in = WildermythManifest.class.getResourceAsStream("/depots.json");
		if(in == null) {
			throw new MissingResourceException("Could not locate depot json");
		}
		JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();

		manifests.clear();
		Map<Long, OS> globalManifestIds = new HashMap<>(); // check cross-OS duplicates

		for (OS os : OS.values()) {
			if (!root.has(os.name())) {
				throw new AssertionError("Missing manifest data for OS: " + os.name());
			}
			JsonObject osNode = root.getAsJsonObject(os.name());

			Multimap<String, ManifestEntry> osManifests = HashMultimap.create();
			Set<Long> seenInOS = new HashSet<>(); // track IDs in this OS

			JsonObject manifestMap = osNode.getAsJsonObject("manifests");
			JsonObject branchesNode = osNode.has("branches") ? osNode.getAsJsonObject("branches") : new JsonObject();
			JsonObject allowedDuplicatesNode = osNode.has("allowedDuplicates") ? osNode.getAsJsonObject("allowedDuplicates") : new JsonObject();

			Map<Long, Set<String>> allowedDuplicates = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : allowedDuplicatesNode.entrySet()) {
				long id = Long.parseLong(entry.getKey());
				Set<String> versions = new HashSet<>();
				entry.getValue().getAsJsonArray().forEach(v -> versions.add(v.getAsString()));
				allowedDuplicates.put(id, versions);
			}

			Map<Long, String> manifestToBranch = new HashMap<>();
			for (Map.Entry<String, JsonElement> branchEntry : branchesNode.entrySet()) {
				String branch = branchEntry.getKey();
				for (JsonElement idElem : branchEntry.getValue().getAsJsonArray()) {
					manifestToBranch.put(idElem.getAsLong(), branch);
				}
			}

			for (Map.Entry<String, JsonElement> manifestEntry : manifestMap.entrySet()) {
				String version = manifestEntry.getKey();
				long manifestID = manifestEntry.getValue().getAsLong();
				String branch = manifestToBranch.getOrDefault(manifestID, "public");

				Collection<ManifestEntry> existing = osManifests.get(branch);
				boolean duplicateOk = allowedDuplicates.getOrDefault(manifestID, Collections.emptySet()).contains(version);
				if (!duplicateOk) {
					boolean exists = existing.stream().anyMatch(me -> me.manifest() == manifestID);
					if (exists) throw new AssertionError("Duplicate manifest " + manifestID + " in OS " + os + " for version " + version);
				}

				// Only check cross-OS duplicates for IDs that haven't already been seen in this OS
				if (!seenInOS.contains(manifestID) && globalManifestIds.containsKey(manifestID)) {
					throw new AssertionError("Manifest " + manifestID + " appears in multiple OS types");
				}

				osManifests.put(branch, new ManifestEntry(version, manifestID));
				globalManifestIds.put(manifestID, os);
				seenInOS.add(manifestID);
			}

			manifests.put(os, osManifests);
		}
	}

	
}
