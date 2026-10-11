package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.nio.file.FileAlreadyExistsException;
import com.google.common.collect.TreeMultimap;
import java.util.Comparator;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.VersionAlreadyWeavedException;
import com.wildermods.thrixlvault.steam.IDownload;
import com.wildermods.thrixlvault.steam.IVaultable;


/**
 * The {@code Weaver} class is responsible for processing (or "weaving") a {@link Vault}
 * by scanning a source directory, computing content-based hashes, and writing any new
 * or updated blobs into the vault's designated blob store. It also serializes a 
 * {@link Chrysalis} manifest inside the vault's designated version directory for later
 * deserialization and validation.
 * <p>
 * If a version has already been weaved (i.e., its manifest already exists) and {@code force}
 * is not enabled, a {@link VersionAlreadyWeavedException} is thrown to prevent accidental overwrites.
 * <p>
 * This class is designed to ensure blob integrity and can validate that all blobs referenced
 * in a manifest exist and are unmodified.
 */
public class Weaver implements IWeaver {
	
	/** Gson instance for serializing Chrysalis manifests. */
	public static final Gson GSON;
	static {
		GsonBuilder builder = new GsonBuilder();
		builder.registerTypeAdapter(Chrysalis.class, new ChrysalisSerializer());
		builder.registerTypeAdapter(VaultContext.class, new VaultContextSerializer().nullSafe());
		builder.registerTypeAdapter(ChrysalisModification.class, new ChrysalisModificationSerializer().nullSafe());
		builder.setPrettyPrinting().setFormattingStyle(FormattingStyle.PRETTY.withIndent("\t"));
		GSON = builder.create();
	}
	
	/** The default output directory (under the user's home directory). */
	public static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home")).resolve("thrixlvault");
	
	/** The directory where blob files are stored. */
	public static final Path BLOB_DIR = OUTPUT_DIR.resolve("blobs");
	
	protected static final Logger LOGGER = LogManager.getLogger();
	
	protected ChrysalisizedVault vault;
	protected final IVaultable artifact;
	protected final Marker marker;
	
	private final WeaveContext context;
	
    /**
     * Constructs a new {@code Weaver} for the given vault and version string.
     *
     * @param vault the vault to weave into
     * @param version the version string to associate with the weaved result
     * @param sourcesDir the directory to scan for files
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity violation occurs
     */
	public Weaver(Vault vault, IVaultable artifact, Path sourcesDir) throws IOException, IntegrityException {
		this(vault, artifact, sourcesDir, false);
	}
	
	public Weaver(Vault vault, IVaultable artifact, Path sourcesDir, String name) throws IOException, IntegrityException {
		this(vault, artifact, sourcesDir, name, false);
	}
	

    /**
     * Constructs a new {@code Weaver} with the option to force overwrite an existing weaved version.
     *
     * @param vault the vault weave into
     * @param version the version string to associate with the weaved result
     * @param sourcesDir the directory to scan for files
     * @param force whether to overwrite existing data
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity violation occurs
     */
	public Weaver(Vault vault, IVaultable artifact, Path sourcesDir, boolean force) throws IOException, IntegrityException {
		this(new WeaveContext(vault, artifact, sourcesDir, "blobs", force));
	}
	
	public Weaver(Vault vault, IVaultable artifact, Path sourcesDir, String name, boolean force) throws IOException, IntegrityException {
		this(new WeaveContext(vault, artifact, sourcesDir, name, force));
	}
	
	private Weaver(WeaveContext context) {
		this.context = context;
		this.marker = MarkerManager.getMarker(context.artifact.name());
		this.artifact = context.artifact;
	}
	
	public Weaver(Vault vault, IDownload download) throws IOException, IntegrityException {
		 this(vault, download, download.dest());
	}
	
    /**
     * Gets the {@link ChrysalisizedVault} produced by this weaving operation.
     *
     * @return the processed vault
     */
	public ChrysalisizedVault getChrysalisizedVault() {
		return vault;
	}
	
    /**
     * Verifies the integrity of all blobs in the weaved vault.
     * This method ensures that all blobs referenced in the manifest exist and are unmodified.
     *
     * @Throws IllegalStateException if this weaver has not yet weaved
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if the version's integrity has been violated
     * @throws InterruptedException 
     * @throws ExecutionException 
     */
	public void verify() throws IntegrityException, IOException, InterruptedException, ExecutionException {
		if(vault == null) {
			throw new IllegalStateException("Not yet weaved!");
		}
		vault.verifyBlobs();
	}
	
	public ChrysalisizedVault weave() throws IOException, IntegrityException {
		if(vault != null) {
			throw new IllegalStateException("Already weaved!");
		}
		vault = weave(context);
		return vault;
	}
	
    /**
     * Internal method that performs the actual weaving process:
     * <ul>
     *   <li>Scans {@code sourceDir} and computes content hashes.</li>
     *   <li>Writes new or changed blobs to {@link Vault#blobDir}.</li>
     *   <li>Writes a versioned Chrysalis manifest JSON file.</li>
     * </ul>
     *
     * @param sourceDir the source directory to scan
     * @param vault the vault receiving the blobs and manifest
     * @param force whether to overwrite existing files
     * @return a new {@link ChrysalisizedVault} instance
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity issue is found
     */
	protected ChrysalisizedVault weave(WeaveContext context) throws IOException, IntegrityException {
		final boolean force = context.force;
		final String name = context.name;
		final Path sourceDir = context.sourcesDir;
		if(!force && Files.exists(context.vault.getChrysalisFile(this, name))) {
			throw new VersionAlreadyWeavedException(artifact.name() + " in " + context.vault.getChrysalisFile(this, name));
		}
		
		final OpenOption[] openOptions = !force ? new StandardOpenOption[] {StandardOpenOption.CREATE_NEW} : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
		Chrysalis chrysalis = ingest(context.vault, sourceDir, new BlobFactory(), force);

		//Serialize and write the chrysalis to the version-specific JSON file
		try {
			Path chrysalisFile = context.vault.getChrysalisFile(this, name);
			Path parent = chrysalisFile.getParent();
			
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			Files.writeString(chrysalisFile, GSON.toJson(chrysalis), openOptions);
			LOGGER.info(marker, "Wrote weaved version to: " + chrysalisFile);
		} catch (IOException e) {
			throw new IOException("Failed to write weaved data to JSON", e);
		}
		
		return context.vault.chrysalisize(this, name);
	}


	/**
	 * Weaves a complete desired directory into a named modification. Missing parent
	 * files become removals, including ignored files. Ignore and tag operations are
	 * explicit. All scanned bytes enter the shared blob store before publishing JSON.
	 * A null parent starts empty; an existing context is never overwritten.
	 */
	public static ChrysalisizedVault weaveModification(Vault vault, IVaultable artifact, String name,
		String parent, Path directory, Set<Path> ignores, Set<Path> removeIgnores,
		Set<String> tags, Set<String> removeTags) throws IOException, IntegrityException {
		return weaveModification(vault, artifact, name, parent, directory, ignores, removeIgnores, tags, removeTags, false);
	}

	/** Replacing a declaration does not overwrite shared blobs. */
	public static ChrysalisizedVault weaveModification(Vault vault, IVaultable artifact, String name,
		String parent, Path directory, Set<Path> ignores, Set<Path> removeIgnores,
		Set<String> tags, Set<String> removeTags, boolean replaceExisting) throws IOException, IntegrityException {
		Vault.requireContextName(name);
		if (name.equals("blobs")) throw new IllegalArgumentException("The default 'blobs' context must be a full snapshot");
		if (!replaceExisting && vault.hasChrysalis(artifact, name)) {
			throw new VersionAlreadyWeavedException(name + " in " + vault.getChrysalisFile(artifact, name));
		}
		Chrysalis base = parent == null ? Chrysalis.EMPTY.clone() : vault.chrysalisize(artifact, parent).getChrysalis();
		String algorithm = base.getBlobFactory().algorithm();
		ChrysalisModification metadata = new ChrysalisModification(algorithm, Map.of(), ignores, removeIgnores);
		metadata.applyTo(base); // Check inherited ignore removals before storing bytes.
		VaultContext declaration = VaultContext.modification(parent, metadata, tags, removeTags);
		// Validate ancestry and tag declarations without publishing a file.
		vault.validateContext(artifact, name, declaration);
		Chrysalis desired = ingest(vault, directory, base.getBlobFactory(), false);
		desired.setIgnoredPaths(base.getIgnoredPaths());
		ChrysalisModification fileChanges = ChrysalisModification.between(base, desired);
		ChrysalisModification modification = new ChrysalisModification(algorithm, fileChanges.changes(), ignores, removeIgnores);
		return vault.saveModification(artifact, name, parent, modification, tags, removeTags, replaceExisting);
	}

	/** Hashes and stores a directory without creating an intermediate manifest. */
	private static Chrysalis ingest(Vault vault, Path directory, BlobFactory factory, boolean force)
		throws IOException, IntegrityException {
		if (!Files.isDirectory(directory)) throw new IOException("Expected a source directory: " + directory);
		List<Path> files;
		try (var paths = Files.walk(directory)) {
			files = paths.filter(Files::isRegularFile).toList();
		}
		Set<Path> processed = ConcurrentHashMap.newKeySet();
		List<Callable<Map.Entry<Hash, Path>>> tasks = new ArrayList<>();
		for (Path file : files) {
			tasks.add(() -> {
				Hash blob = BlobIO.hash(factory, file);
				Path destination = vault.blobDir.resolve(blob.hash());
				if (processed.add(destination)) {
					if (!force && Files.exists(destination)) {
						BlobIO.verify(factory, destination, blob);
					} else {
						Path staging = Files.createTempFile(vault.blobDir, ".weave-", ".tmp");
						try {
							Files.copy(file, staging, StandardCopyOption.REPLACE_EXISTING);
							BlobIO.verify(factory, staging, blob);
							try {
								if (force) Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
								else Files.move(staging, destination);
							} catch (FileAlreadyExistsException race) {
								BlobIO.verify(factory, destination, blob);
							}
						} finally {
							Files.deleteIfExists(staging);
						}
					}
				}
				return Map.entry(blob, directory.relativize(file));
			});
		}
		var blobs = TreeMultimap.<Hash, Path>create(Comparator.comparing(Hash::hash), Comparator.naturalOrder());
		try (var executor = Executors.newFixedThreadPool(Math.max(1, Math.min(files.size(), Runtime.getRuntime().availableProcessors())))) {
			for (var future : executor.invokeAll(tasks)) {
				try {
					var entry = future.get();
					blobs.put(entry.getKey(), entry.getValue());
				} catch (ExecutionException e) {
					if (e.getCause() instanceof IOException io) throw io;
					if (e.getCause() instanceof IntegrityException integrity) throw integrity;
					throw new IOException("Failed to weave directory " + directory, e.getCause());
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Weaving interrupted", e);
		}
		Chrysalis result = Chrysalis.EMPTY.clone().factory(factory);
		result.setBlobs(blobs);
		LOGGER.info("Weaved {} files, {} unique blobs, {} duplicate paths", blobs.size(), blobs.keySet().size(), blobs.size() - blobs.keySet().size());
		return result;
	}


	@Override
	public String name() {
		return artifact.name();
	}


	@Override
	public Path artifactPath() {
		return artifact.artifactPath();
	}
	
	public IVaultable getArtifact() {
		return artifact;
	}
	
	protected static final record WeaveContext(Vault vault, IVaultable artifact, Path sourcesDir, String name, boolean force) {}
	
}
