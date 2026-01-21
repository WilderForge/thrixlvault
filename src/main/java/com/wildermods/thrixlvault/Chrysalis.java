package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
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
	
	static final Logger LOGGER = LogManager.getLogger();
	
	/**
	 * Creates an empty {@code Chrysalis}. 
	 * <p>
	 * This constructor is used internally for cloning and for JSON deserialization.
	 * Callers should use {@link #fromDir(Path)} to construct a Chrysalis from real data.
	 * </p>
	 */
	Chrysalis() {
		// NO-OP
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
	 *
	 * @param dir the directory to walk and hash
	 * @param forEachBlob a callback invoked for every {@link Blob} encountered; the wrapped
	 *                    {@link Path} may be modified before being added to the result map
	 * @throws IOException if file traversal or hashing fails
	 */
	Chrysalis(Path dir, BiConsumer<Reference<Path>, IBlob> forEachBlob) throws IOException {
		super(Files.walk(dir), forEachBlob);
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
	 * @param path the JSON file containing a serialized Chrysalis
	 * @return the deserialized {@code Chrysalis}
	 * 
	 * @throws IOException if the file cannot be read
	 * @throws JsonSyntaxException if the JSON is invalid
	 * @throws JsonIOException if deserialization fails
	 */
	public static Chrysalis fromFile(Path path) throws JsonSyntaxException, JsonIOException, IOException {
		return Weaver.GSON.fromJson(Files.newBufferedReader(path), Chrysalis.class);
	}
	
	/**
	 * Compares this {@code Chrysalis} to another object for equality.
	 * <p>
	 * Two Chrysalis instances are considered equal if and only if their underlying
	 * hash-path mappings contain exactly the same entries. The comparison is
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
			return this.blobs.equals(((Chrysalis)o).blobs);
		}
		return false;
	}
	
	/**
	 * Returns a deep copy of this {@code Chrysalis}.
	 * <p>
	 * The underlying multimap is reconstructed with the same hash-path pairs and
	 * wrapped again in a synchronized {@code SetMultimap}.
	 * </p>
	 */
	@Override
	public Chrysalis clone() {
		Chrysalis chrysalis = new Chrysalis();
		
		chrysalis.blobs = TreeMultimap.create(
			Comparator.comparing(Hash::hash),
			Ordering.natural()
		);

		for(Hash key : this.blobs.keySet()) {
			for(Path path : this.blobs.values()) {
				chrysalis.blobs.put(key, path);
			}
		}
		
		chrysalis.blobs = Multimaps.synchronizedSetMultimap(chrysalis.blobs);
		
		return chrysalis;
	}
	
	/**
	 * Returns the multimap of computed {@link Hash} values to the file {@link Path}s
	 * from which those hashes were generated.
	 *
	 * @return a thread-safe multimap of hash→paths
	 */
	public SetMultimap<Hash, Path> blobs() {
		return blobs;
	}

	/**
	 * @deprecated This method is intended for internal use only.
	 * Normal code should not mutate a Chrysalis after creation.</p>
	 */
	@Deprecated(forRemoval = false)
	public void setBlobs(SetMultimap<Hash, Path> blobs) {
		this.blobs = blobs;
	}
	
	public BlobFactory getBlobFactory() {
		return blobFactory;
	}
	
	public Chrysalis factory(BlobFactory factory) {
		this.blobFactory = factory;
		return this;
	}
	
}
