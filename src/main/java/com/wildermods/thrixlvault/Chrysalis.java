package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import com.wildermods.masshash.Blob;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.Hasher;
import com.wildermods.masshash.IBlob;
import com.wildermods.masshash.utils.Reference;

/**
 * A {@code Chrysalis} represents the processed (hashed) state of a directory of files.
 * <p>
 * Internally, it maps each file's computed {@link Hash} to the {@link Path} from which
 * the file was read. Multiple paths may share the same hash if their contents are identical.
 * </p>
 *
 * <p>
 * A Chrysalis is typically produced from a directory using {@link #fromDir(Path)} and is
 * serialized/deserialized as JSON by the {@link Weaver}. It is primarily used by
 * {@link ChrysalisizedVault} to verify that a vault contains the correct files for a
 * specific game version.
 * </p>
 *
 * <p>
 * Hashing behavior, parallelism, and file processing rules are inherited from
 * {@link Hasher}.
 * </p>
 *
 * <p>
 * Chrysalis should be treated as immutable snapshots.
 * </p>
 */
public class Chrysalis extends Hasher<Chrysalis> implements Cloneable {

	private Set<Path> ignoredPaths = Set.of();
	private volatile SetMultimap<Hash, Path> activeBlobsCache;

	/** @return immutable, explicit ignore roots retained by this snapshot */
	public Set<Path> getIgnoredPaths() {
		return ignoredPaths;
	}

	/**
	 * Checks whether a relative path or any ancestor has an explicit ignore.
	 * This is a lexical check and never accesses the filesystem. A root covers
	 * both itself and all descendants, regardless of whether it currently exists.
	 */
	public boolean isIgnored(Path path) {
		Path normalized = ChrysalisModification.normalizePath(path);
		for (Path current = normalized; current != null; current = current.getParent()) {
			if (ignoredPaths.contains(current)) {
				return true;
			}
		}
		return false;
	}

	synchronized void setIgnoredPaths(Set<Path> ignoredPaths) {
		Set<Path> normalized = new HashSet<>();
		for (Path path : Objects.requireNonNull(ignoredPaths, "ignoredPaths")) {
			normalized.add(ChrysalisModification.normalizePath(path));
		}
		this.ignoredPaths = Set.copyOf(normalized);
		activeBlobsCache = null;
	}

	/**
	 * Returns only expectations visible to checking and materialization. Stored
	 * expectations remain in {@link #blobs()} so removing an ignore restores them.
	 * Shared hashes remain present whenever at least one path is not ignored.
	 * The immutable result is computed lazily and reused. With no ignores it is
	 * the stored immutable multimap itself. Internal setters invalidate the cache.
	 *
	 * @return an immutable hash-to-paths map with ignored paths excluded
	 */
	public SetMultimap<Hash, Path> activeBlobs() {
		SetMultimap<Hash, Path> cached = activeBlobsCache;
		if (cached == null) {
			synchronized (this) {
				cached = activeBlobsCache;
				if (cached == null) {
					if (ignoredPaths.isEmpty()) {
						cached = blobs;
					} else {
						ImmutableSetMultimap.Builder<Hash, Path> active = ImmutableSetMultimap.builder();
						blobs.forEach((hash, path) -> {
							if (!isIgnored(path)) {
								active.put(hash, path);
							}
						});
						cached = active.build();
					}
					activeBlobsCache = cached;
				}
			}
		}
		return cached;
	}

	// Task-local view only. Composition must use the original, retained snapshot.
	synchronized Chrysalis checkingSnapshot() {
		Chrysalis active = new Chrysalis();
		active.factory(blobFactory);
		active.blobs = activeBlobs();
		active.activeBlobsCache = active.blobs;
		active.ignoredPaths = ignoredPaths;
		return active;
	}
	
	protected static final Logger LOGGER = LogManager.getLogger();
	private static final SetMultimap<Hash, Path> EMPTY_SETMULTIMAP = ImmutableSetMultimap.of();
	public static final Chrysalis EMPTY = new Chrysalis();
	static {
		EMPTY.blobs = EMPTY_SETMULTIMAP;
	}
	
	/**
	 * Creates an empty {@code Chrysalis}. 
	 * <p>
	 * This constructor is used internally for cloning and for JSON deserialization.
	 * Callers should use {@link #fromDir(Path)} to construct a Chrysalis from real data.
	 * </p>
	 * 
	 * @deprecated Chrysalis should generally be created from a Vault
	 */
	@Deprecated(forRemoval = false)
	protected Chrysalis() {
		blobs = ImmutableSetMultimap.of();
	}
	
	/**
	 * Constructs a {@code Chrysalis} by hashing all files under the given directory.
	 * <p>
	 * This delegates to the {@link Hasher} constructor, which:
	 * <ul>
	 *   <li>walks the directory recursively,</li>
	 *   <li>produces a {@link Blob} for each file,</li>
	 *   <li>invokes {@code forEachBlob} with a modifiable {@link Reference}&lt;Path&gt;,</li>
	 *   <li>computes the content hash,</li>
	 *   <li>stores the resulting hash→path mapping in a sorted, thread-safe multimap.</li>
	 * </ul>
	 * </p>
	 * @deprecated Chrysalis should generally be created from a Vault
	 * @param dir the directory to walk and hash
	 * @param forEachBlob a callback invoked for every {@link Blob} encountered; the wrapped
	 *                    {@link Path} may be modified before being added to the result map
	 * @throws IOException if file traversal or hashing fails
	 */
	@Deprecated(forRemoval = false)
	protected Chrysalis(Path dir, BiConsumer<Reference<Path>, IBlob> forEachBlob) throws IOException {
		super(Files.walk(dir), forEachBlob);
		setBlobs(blobs);
	}

	/**
	 * Creates a {@code Chrysalis} by hashing all files under the given directory
	 * using default behavior (no per-blob callback).
	 *
	 * @param path the directory to process
	 * @return a newly computed {@code Chrysalis}
	 * @throws IOException if file traversal or hashing fails
	 */
	public static Chrysalis fromDir(Path path) throws IOException {
		return fromDir(path, (p, blob) -> {});
	}

	/**
	 * Creates a {@code Chrysalis} by hashing all files under the given directory,
	 * invoking the supplied callback for each {@link Blob} encountered.
	 *
	 * @param path the directory to process
	 * @param forEachBlob a callback invoked for each Blob; the path reference may
	 *                    be modified (e.g., relativized) before storage
	 * @return a newly computed {@code Chrysalis}
	 * @throws IOException if file traversal or hashing fails
	 */
	public static Chrysalis fromDir(Path path, BiConsumer<Reference<Path>, IBlob> forEachBlob) throws IOException {
		LOGGER.info("Constructing Chrysalis from " + path);
		return new Chrysalis(path, forEachBlob);
	}
	
	/**
	 * Loads a {@code Chrysalis} from a previously serialized JSON file.
	 * <p>
	 * The file must have been written by {@link Weaver#GSON}; all hash-path mappings
	 * are recreated exactly as stored.
	 * </p>
	 *
	 * @deprecated Use {@link Vault#load(com.wildermods.thrixlvault.steam.IVaultable, Path)} to load either declaration type with its vault context.
	 * @param path the JSON file containing a serialized Chrysalis
	 * @return the deserialized {@code Chrysalis}
	 * 
	 * @throws IOException if the file cannot be read
	 * @throws JsonSyntaxException if the JSON is invalid
	 * @throws JsonIOException if deserialization fails
	 */
	@Deprecated(forRemoval = false)
	public static Chrysalis fromFile(Path path) throws JsonSyntaxException, JsonIOException, IOException {
		try (var reader = Files.newBufferedReader(path)) {
			VaultContext context = Objects.requireNonNull(Weaver.GSON.fromJson(reader, VaultContext.class),
				"Snapshot file contains null");
			return context.snapshot().orElseThrow(() -> new IOException(
				"A modification must be resolved through its Vault: " + path));
		}
	}
	
	/**
	 * Compares this {@code Chrysalis} to another object for equality.
	 * <p>
	 * Two Chrysalis instances are considered equal if and only if their underlying
	 * hash-path mappings and explicit ignore roots contain the same entries. The comparison is
	 * structural and does not depend on object identity.
	 * </p>
	 *
	 * @param o the object to compare against
	 * @return {@code true} if {@code o} is a {@code Chrysalis} with an equivalent
	 *         mapping of {@link Hash} to {@link Path}; {@code false} otherwise
	 */
	@Override
	public boolean equals(Object o) {
		if(o instanceof Chrysalis) {
			return this.blobs.equals(((Chrysalis)o).blobs)
				&& this.ignoredPaths.equals(((Chrysalis)o).ignoredPaths);
		}
		return false;
	}
	
	/**
	 * Returns a distinct snapshot sharing this snapshot's immutable data.
	 * <p>
	 * Stored expectations, ignore roots, and an already computed active map may
	 * be shared safely. Internal setters on either snapshot replace that snapshot's
	 * data and invalidate only its own cache.
	 * </p>
	 */
	@Override
	public synchronized Chrysalis clone() {
		Chrysalis chrysalis = new Chrysalis();
		chrysalis.factory(blobFactory);
		chrysalis.ignoredPaths = ignoredPaths;
		
		chrysalis.blobs = blobs;
		chrysalis.activeBlobsCache = activeBlobsCache;
		
		return chrysalis;
	}
	
	/**
	 * Returns the multimap of computed {@link Hash} values to the file {@link Path}s
	 * from which those hashes were generated.
	 *
	 * Ignored expectations are retained here. Use {@link #activeBlobs()} when
	 * checking or materializing the effective file set.
	 * @return an immutable multimap of all stored hash→paths
	 */
	public SetMultimap<Hash, Path> blobs() {
		return blobs;
	}

	/**
	 * Copies expectations into an immutable map and invalidates the active cache.
	 * The input map may be changed afterwards without affecting this snapshot.
	 * @deprecated This method is intended for internal use only.
	 * Normal code should not mutate a Chrysalis after creation.</p>
	 */
	@Deprecated(forRemoval = false)
	public synchronized void setBlobs(SetMultimap<Hash, Path> blobs) {
		Objects.requireNonNull(blobs, "blobs");
		synchronized (blobs) {
			this.blobs = ImmutableSetMultimap.copyOf(blobs);
		}
		activeBlobsCache = null;
	}
	
	public BlobFactory getBlobFactory() {
		return blobFactory;
	}
	
	public Chrysalis factory(BlobFactory factory) {
		this.blobFactory = factory;
		return this;
	}
	
}
