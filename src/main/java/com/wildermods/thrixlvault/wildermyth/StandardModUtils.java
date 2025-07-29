package com.wildermods.thrixlvault.wildermyth;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.file.PathUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import org.myjtools.mavenfetcher.FetchedArtifact;
import org.myjtools.mavenfetcher.MavenFetchRequest;
import org.myjtools.mavenfetcher.MavenFetchResult;
import org.myjtools.mavenfetcher.MavenFetcher;

import com.google.common.collect.LinkedHashMultimap;
import com.wildermods.thrixlvault.Chrysalis;
import com.wildermods.thrixlvault.ChrysalisizedVault;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.exception.UnknownVersionException;
import com.wildermods.thrixlvault.steam.IVaultable;
import com.wildermods.thrixlvault.steam.IVersioned;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.graph.AssetNode;

public class StandardModUtils {
	
	public static final Logger LOGGER = LogManager.getLogger("ThrixlVault");
	public static final Marker FETCH = MarkerManager.getMarker("FETCH");
	public static final Marker DOWNLOAD = MarkerManager.getMarker("DOWNLOAD");
	public static final Marker EXTRACT = MarkerManager.getMarker("EXTRACT");
	public static final Marker CHECKMOD = MarkerManager.getMarker("CHECKMOD");
	
	private static final Path tempPath;
	static {
		try {
			tempPath = Files.createTempDirectory("temp");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void main(String[] args) throws UnknownVersionException, IOException, InterruptedException, ExecutionException, MissingVersionException {
		Path[] dirs = new Path[args.length];
		for(int i = 0; i < dirs.length; i++) {
			dirs[i] = Path.of(args[i]);
		}
		IVersioned version = WildermythManifest.getLatest();
		System.out.println("Latest version is: " + version);
		checkMods(version, dirs);
		
	}
	
	public static void checkMods(Path... mods) throws IOException, UnknownVersionException, InterruptedException, ExecutionException, MissingVersionException {
		checkMods(WildermythManifest.getLatest(), mods);
	}
	
	public static void checkMods(IVersioned gameVersion, Path... mods) throws IOException, InterruptedException, ExecutionException, MissingVersionException {
		if(mods.length == 0) {
			mods = new Path[] {Path.of("mods").resolve("user"), Path.of(".")};
		}
		ArrayList<Path> modList = new ArrayList<>();
		modList.addAll(Arrays.asList(mods));
		for(Path path : mods) {
			if(path.endsWith(Path.of("mods").resolve("user"))) {
				modList.remove(path);
				if(Files.exists(path)) {
					Files.list(path).filter((p) -> Files.exists(p) && !p.endsWith(Path.of("mods").resolve("user").resolve("villain_heartOfTheForest"))).forEach((modPath -> {
						modList.add(modPath);
					}));
				}
			}
			else {
				modList.add(path);
			}
		}
		Map<WildermythMod, ChrysalisizedVault> modBlobs = chrysalisizeMods(gameVersion, modList);
		LinkedHashMap<Path, AssetNode> assets = new LinkedHashMap<>();
		
		for(Entry<WildermythMod, ChrysalisizedVault> e : modBlobs.entrySet()) {
			WildermythMod mod = e.getKey();
			Marker marker = MarkerManager.getMarker(mod.modid());
			Chrysalis modChrysalis = e.getValue().getChrysalis();
			Set<Path> modFiles;
			
			if(mod.getModFile() != null) {
				modFiles = modChrysalis.blobs().values().stream().filter(path -> !path.startsWith("modInjections")).map(path -> mod.getModFile().relativize(path)).collect(Collectors.toSet());
			}
			else {
				modFiles = modChrysalis.blobs().values().stream().filter(path -> !path.startsWith("modInjections")).collect(Collectors.toSet());
			}
			
			Set<Path> injectionTargets = new HashSet<>();
			
			for(Path p : modFiles) {
				System.out.println(p);
			}
			
			for(Path p : injectionTargets) {
				System.out.println("Injection target: " + p);
			}
			
			/*cvault.computeOverBlobs((hash, vault, chrysalis) -> {
				HashSet<Path> overwritten = new HashSet<>();
				for(Path path : chrysalis.blobs().get(hash)) {
					if(path.startsWith("assets")) {
						if (modFiles.stream().anyMatch(p -> p.equals(path))) {
							overwritten.add(path);
							LOGGER.info(marker, " overwrites base game file " + path);
						}
					}
				}
			});*/
		}
		
	}
	
	
	public static HashMap<WildermythMod, ChrysalisizedVault> chrysalisizeMods(IVersioned gameVersion, ArrayList<Path> mods) throws IOException, InterruptedException, ExecutionException, MissingVersionException {
		ArrayList<WildermythMod> retrievedMods = new ArrayList<>();
		for(Path path : mods) {
			WildermythMod mod = getMod(path);
			if(mod != null) {
				retrievedMods.add(mod);
			}
			else {
				LOGGER.warn(CHECKMOD, "Could not retrieve mod at " + path);
			}
		}
		
		LinkedHashMap<WildermythMod, ChrysalisizedVault> chrysalidi = new LinkedHashMap<>();
		
		Wildermyth wildermyth = new Wildermyth(gameVersion);
		ChrysalisizedVault cvault = fetchChrysalis(wildermyth);
		chrysalidi.put(wildermyth, cvault);
		
		for(WildermythMod mod : retrievedMods) {
			Vault modVault = new Vault(mod.getModFile());
			Marker marker = MarkerManager.getMarker(mod.modid());
			Path assetDir = mod.getModFile().resolve("assets");
			if (Files.isDirectory(assetDir)) {
				if(Files.newDirectoryStream(assetDir).iterator().hasNext()) { //if directory is not empty (chrysalis can't be made with zero files)
					Chrysalis modChrysalis = Chrysalis.fromDir(assetDir);
					chrysalidi.put(mod, modVault.chrysalisize(mod, modChrysalis));
				}
				else {
					LOGGER.warn(marker, "No mod assets. Mod will not be processed.");
				}
			}
		}
		return chrysalidi;
	}
	
	public static WildermythMod getMod(Path path) {
		try {
			return WildermythMod.get(path);
		} catch (Exception e) {
			LOGGER.warn(CHECKMOD, "Could not retrieve mod at " + path);
			LOGGER.catching(Level.WARN, e);
			return null;
		}
	}
	
	public static Path downloadGameMetadata() throws IOException {
		try {
			return downloadGameMetadata(WildermythManifest.getLatest());
		} catch (UnknownVersionException e) {
			throw new AssertionError(e);
		}
	}
	
	public static Path downloadGameMetadata(String version) throws IOException {
		return downloadGameMetadata(() -> version);
	}
	
	public static Path downloadGameMetadata(String version, OS os) throws IOException {
		return downloadGameMetadata(() -> version, os);
	}
	
	public static Path downloadGameMetadata(IVersioned version) throws IOException {
		return downloadGameMetadata(version, OS.getOS());
	}
	
	public static Path downloadGameMetadata(WildermythManifest manifest) throws IOException {
		return downloadGameMetadata(manifest, manifest.os());
	}
	
	/**
	 * Downloads the game metadata for the specified game version, for the specified OS.
	 * 
	 * @param location
	 * @param version
	 * @param os
	 * @return
	 * @throws UnknownVersionException if the version isn't recognized
	 * @throws IOException
	 */
	public static Path downloadGameMetadata(IVersioned version, OS os) throws IOException {
		MavenFetcher fetcher = new MavenFetcher();
		fetcher.clearRemoteRepositories();
		fetcher.addRemoteRepository("wilderforge", "https://maven.wildermods.com");
		
		MavenFetchRequest fetchRequest = new MavenFetchRequest("com.wildermods.wildermyth:" + os.name().toLowerCase() + ".metadata:" + version.version().replace('+', '-') + "-SNAPSHOT");
		File localRepoDir = new File(System.getProperty("user.home"), ".m2/repository");
		fetcher.localRepositoryPath(localRepoDir.toPath());
		MavenFetchResult result = fetcher.fetchArtifacts(fetchRequest);
		
		if(result.hasErrors()) {
			result.errors().forEach((exception) -> {
				exception.printStackTrace();
			});
			throw new RuntimeException("Failed to retrieve one or more artifacts.");
		}
		
		Path unzipped = tempPath.resolve(("wildermyth-metadata-unzip"));

		Path downloadedZip = tempPath.resolve("wildermyth_metadata.zip");
		for (FetchedArtifact artifact : result.artifacts().collect(Collectors.toList())) {
			LOGGER.info(DOWNLOAD, "Downloaded artifact " + artifact.coordinates());
			// Save the downloaded artifact to the temp zip location
			Files.copy(artifact.path(), downloadedZip, StandardCopyOption.REPLACE_EXISTING);

			if (artifact.coordinates().startsWith("com.wildermods.wildermyth")) {
				LOGGER.info(EXTRACT, "Extracting " + artifact.coordinates() + " to " + unzipped);
				try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(downloadedZip))) {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						Path outPath = unzipped.resolve(entry.getName());
						if(!outPath.toAbsolutePath().toString().contains(WildermythManifest.GAME_NAME + File.separator + os.name() + File.separator + version.version())) {
							LOGGER.trace(EXTRACT, "Skipping " + outPath);
							continue;
						}
						if (entry.isDirectory()) {
							Files.createDirectories(outPath);
							LOGGER.trace(EXTRACT, "Created " + outPath);
						} else {
							Files.createDirectories(outPath.getParent());
							Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
							LOGGER.trace(EXTRACT, "Extracted " + outPath);
						}
						zis.closeEntry();
					}
				}
			}
		}
		
		if(result.hasErrors()) {
			IOException ex = new IOException("Could not download " + version + " for os " + os);
			result.errors().forEach((e) -> {
				ex.addSuppressed(e);
			});
			throw ex;
		}
		
		return unzipped;
	}
	
	public static ChrysalisizedVault fetchChrysalis(String version) throws IOException {
		return fetchChrysalis(version, OS.getOS());
	}
	
	public static ChrysalisizedVault fetchChrysalis(String version, OS os) throws IOException {
		return fetchChrysalis(() -> version, os);
	}
	
	public static ChrysalisizedVault fetchChrysalis(IVersioned version) throws IOException {
		return fetchChrysalis(version, OS.getOS());
	}
	
	public static ChrysalisizedVault fetchChrysalis(WildermythManifest manifest) throws IOException {
		return fetchChrysalis(manifest, manifest.os());
	}
	
	public static ChrysalisizedVault fetchChrysalis(IVersioned version, OS os) throws IOException {
		WildermythManifest manifest;
		Vault vault = Vault.DEFAULT;
		ChrysalisizedVault cvault = null;
		Path downloaded = null;
		try {
			manifest = WildermythManifest.get(os, version.version());
			cvault = Vault.DEFAULT.chrysalisize(manifest);
			LOGGER.info(FETCH, "Found Chrysalisized " + manifest + " at " + cvault);
			return cvault;
		} catch(IOException e) {
			LOGGER.catching(Level.ERROR, e);
		} catch (UnknownVersionException e) {
			LOGGER.warn(FETCH, "Unknown version provided:  " + version.version());
		} catch (MissingVersionException e) {
			LOGGER.warn(FETCH, "Version " + version + " not chrysalisized in " + vault);
		}
		
		if(cvault == null) {
			LOGGER.warn(DOWNLOAD, "No chrysalisized vault. Attempting to download.");
			downloaded = downloadGameMetadata(version, os);
		}
		
		if(downloaded == null) {
			throw new AssertionError();
		}
		
		try {
			final Path artifactPath = Path.of(WildermythManifest.GAME_NAME).resolve(os.name()).resolve(version.version());
			LOGGER.info(FETCH, vault.vaultDir.resolve(artifactPath));
			Files.createDirectories(artifactPath);
			PathUtils.copyDirectory(downloaded, vault.vaultDir, StandardCopyOption.REPLACE_EXISTING);
			cvault = Vault.DEFAULT.chrysalisize(new IVaultable(){

				@Override
				public String name() {
					return version.version();
				}

				@Override
				public Path artifactPath() {
					return Path.of(WildermythManifest.GAME_NAME).resolve(os.name()).resolve(version.version());
				}
				
			});
		} catch (MissingVersionException e) {
			NoSuchFileException nsfe = new NoSuchFileException(e.getMessage());
			nsfe.initCause(e);
			throw nsfe;
		}
		
		return cvault;
	}
}
