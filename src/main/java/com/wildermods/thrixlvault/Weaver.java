package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.common.collect.TreeMultimap;
import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.VersionAlreadyWeavedException;
import com.wildermods.thrixlvault.exception.DatabaseVaultCircularityError;
import com.wildermods.thrixlvault.steam.IDownload;
import com.wildermods.thrixlvault.steam.IVaultable;


/**
 * The {@code Weaver} class is responsible for processing (or "weaving") a {@link Vault}
 * by scanning a source directory, computing content-based hashes, and writing any new
 * or updated blobs into the vault's designated blob store. It also serializes a 
 * snapshot or modification declaration inside the vault's designated version directory for later
 * deserialization and validation.
 * <p>
 * If a version has already been weaved (i.e., its manifest already exists) and {@code force}
 * is not enabled, a {@link VersionAlreadyWeavedException} is thrown to prevent accidental overwrites.
 * <p>
 * Fluent methods configure the name, parent, ignores, tags and force behavior before
 * {@link #weave()} starts. Without a parent it saves a standalone snapshot; with a
 * parent it saves a modification of the complete desired source directory.
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
	
	protected volatile ChrysalisizedVault vault;
	protected final IVaultable artifact;
	protected final Marker marker;
	
	private final WeaveContext context;
	private String contextName;
	private String parentName;
	private boolean force;
	private final Set<Path> ignores = new LinkedHashSet<>();
	private final Set<Path> removeIgnores = new LinkedHashSet<>();
	private final Set<String> tags = new LinkedHashSet<>();
	private final Set<String> removeTags = new LinkedHashSet<>();
	private WeaveContext frozenContext;
	private boolean weaving;
	
    /**
     * Constructs a new {@code Weaver} for the given vault and version string.
     *
     * @param vault the vault to weave into
     * @param artifact the versioned artifact to associate with the result
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
     * @param artifact the versioned artifact to associate with the result
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
		this.contextName = context.name;
		this.parentName = context.parent;
		this.force = context.force;
		this.ignores.addAll(context.ignores);
		this.removeIgnores.addAll(context.removeIgnores);
		this.tags.addAll(context.tags);
		this.removeTags.addAll(context.removeTags);
	}
	
	public Weaver(Vault vault, IDownload download) throws IOException, IntegrityException {
		 this(vault, download, download.dest());
	}
	
	/** Selects the output declaration name; defaults to {@code blobs}. */
	public synchronized Weaver name(String name) {
		ensureConfigurable();
		this.contextName = Vault.requireContextName(name);
		return this;
	}

	/** Selects a parent in the same artifact directory; null produces a full snapshot. */
	public synchronized Weaver parent(String name) {
		ensureConfigurable();
		this.parentName = name == null ? null : Vault.requireContextName(name);
		return this;
	}

	/**
	 * Permits replacing the output declaration. For standalone snapshots this also
	 * repairs stored blobs, matching the existing force constructors. Child weaving
	 * verifies and reuses shared blobs without overwriting them.
	 */
	public synchronized Weaver force(boolean force) {
		ensureConfigurable();
		this.force = force;
		return this;
	}

	/** Adds explicit ignore roots. Their bytes are still stored for later restoration. */
	public synchronized Weaver ignore(Path... paths) {
		ensureConfigurable();
		ignores.addAll(normalizePaths(paths));
		return this;
	}

	/** Removes exact inherited ignore roots; roots must exist in the parent. */
	public synchronized Weaver removeIgnore(Path... paths) {
		ensureConfigurable();
		removeIgnores.addAll(normalizePaths(paths));
		return this;
	}

	/** Adds local tags; unknown tags are preserved. */
	public synchronized Weaver tag(String... tags) {
		ensureConfigurable();
		this.tags.addAll(validateTags(tags));
		return this;
	}

	/** Subtracts inherited tags; removing an absent tag is harmless. */
	public synchronized Weaver removeTag(String... tags) {
		ensureConfigurable();
		removeTags.addAll(validateTags(tags));
		return this;
	}

	private void ensureConfigurable() {
		if (frozenContext != null) throw new IllegalStateException("Configuration is fixed once weaving starts");
	}

	private Set<Path> normalizePaths(Path[] paths) {
		Set<Path> normalized = new LinkedHashSet<>();
		for (Path path : Objects.requireNonNull(paths, "paths")) normalized.add(ChrysalisModification.normalizePath(path));
		return normalized;
	}

	private Set<String> validateTags(String[] tags) {
		Set<String> validated = new LinkedHashSet<>();
		for (String tag : Objects.requireNonNull(tags, "tags")) {
			if (Objects.requireNonNull(tag, "tag").isBlank()) throw new IllegalArgumentException("Tags must not be blank");
			validated.add(tag);
		}
		return validated;
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
     * @throws IllegalStateException if this weaver has not yet weaved
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
	
	/**
	 * Hashes the complete source directory, stores its blobs and saves either a full
	 * snapshot or a delta against the explicit parent. Configuration is frozen when
	 * this method first starts. Failed weaving may be retried with that same configuration.
	 */
	public ChrysalisizedVault weave() throws IOException, IntegrityException {
		synchronized (this) {
			if (vault != null || weaving) throw new IllegalStateException("Already weaved or weaving");
			if (frozenContext == null) {
				frozenContext = new WeaveContext(context.vault, artifact, context.sourcesDir, contextName, force,
					parentName, ignores, removeIgnores, tags, removeTags);
			}
			weaving = true;
		}
		try {
			vault = weave(frozenContext);
			return vault;
		} finally {
			synchronized (this) { weaving = false; }
		}
	}

	/** Shared snapshot/modification workflow; publishes JSON only after storing all bytes. */
	protected ChrysalisizedVault weave(WeaveContext context) throws IOException, IntegrityException {
		Vault destination = context.vault;
		String name = context.name;
		String parent = context.parent;
		if (parent != null && name.equals("blobs")) {
			throw new IllegalArgumentException("The default 'blobs' context must be a full snapshot");
		}
		if (name.equals(parent)) throw new DatabaseVaultCircularityError(destination, name + ".json", name + ".json");
		if (!context.force && destination.hasChrysalis(artifact, name)) {
			throw new VersionAlreadyWeavedException(artifact.name() + " in " + destination.getChrysalisFile(artifact, name));
		}
		Chrysalis base = parent == null ? Chrysalis.EMPTY.clone() : destination.chrysalisize(artifact, parent).getChrysalis();
		String algorithm = base.getBlobFactory().algorithm();
		ChrysalisModification metadata = new ChrysalisModification(algorithm, Map.of(), context.ignores, context.removeIgnores);
		metadata.applyTo(base);
		VaultContext declaration = VaultContext.modification(parent, metadata, context.tags, context.removeTags);
		destination.validateContext(artifact, name, declaration);

		Chrysalis desired = ingest(destination, context.sourcesDir, base.getBlobFactory(), context.force && parent == null);
		desired.setIgnoredPaths(base.getIgnoredPaths());
		ChrysalisModification fileChanges = ChrysalisModification.between(base, desired);
		ChrysalisModification modification = new ChrysalisModification(algorithm, fileChanges.changes(), context.ignores, context.removeIgnores);
		ChrysalisizedVault result = destination.saveContext(artifact, name,
			VaultContext.modification(parent, modification, context.tags, context.removeTags), context.force);
		LOGGER.info(marker, "Wrote weaved version to: " + result.getChrysalisFile());
		return result;
	}

	/** Hashes and stores a directory without creating an intermediate manifest. */
	private Chrysalis ingest(Vault vault, Path directory, BlobFactory factory, boolean force)
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
				Hash blob = factory.blob(file);
				Path destination = vault.blobDir.resolve(blob.hash());
				if (processed.add(destination)) {
					if (!force && Files.exists(destination)) {
						factory.blob(destination, blob).verify();
					} else {
						Path staging = Files.createTempFile(vault.blobDir, ".weave-", ".tmp");
						try {
							Files.copy(file, staging, StandardCopyOption.REPLACE_EXISTING);
							factory.blob(staging, blob).verify();
							try {
								if (force) Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
								else Files.move(staging, destination);
							} catch (FileAlreadyExistsException race) {
								factory.blob(destination, blob).verify();
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
	
	protected static final record WeaveContext(Vault vault, IVaultable artifact, Path sourcesDir,
		String name, boolean force, String parent, Set<Path> ignores, Set<Path> removeIgnores,
		Set<String> tags, Set<String> removeTags) {
		public WeaveContext(Vault vault, IVaultable artifact, Path sourcesDir, String name, boolean force) {
			this(vault, artifact, sourcesDir, name, force, null, Set.of(), Set.of(), Set.of(), Set.of());
		}
		public WeaveContext {
			Objects.requireNonNull(vault, "vault");
			Objects.requireNonNull(artifact, "artifact");
			Objects.requireNonNull(sourcesDir, "sourcesDir");
			Vault.requireContextName(name);
			if (parent != null) Vault.requireContextName(parent);
			ignores = Set.copyOf(ignores);
			removeIgnores = Set.copyOf(removeIgnores);
			tags = Set.copyOf(tags);
			removeTags = Set.copyOf(removeTags);
		}
	}
	
}
