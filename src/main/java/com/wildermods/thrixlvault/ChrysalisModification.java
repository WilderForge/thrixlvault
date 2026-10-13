package com.wildermods.thrixlvault;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;

/**
 * An immutable set of path-specific changes to a {@link Chrysalis}.
 * <p>
 * Each path has at most one change in a modification. Any number of paths may
 * refer to the same hash, and changing one path never changes another copy of
 * that blob. Separate modifications may be applied in sequence to change the
 * same path more than once.
 * <p>
 * Additions require the path to be absent. Replacements and removals require
 * its current hash to match their expected hash. Applying a modification builds
 * a new snapshot without mutating the input or reading or writing blob bytes.
 * Replacement and added blobs must be stored separately before export.
 */
public final class ChrysalisModification {

	/** The kind of change to a file expectation. */
	public enum Operation { ADD, REPLACE, REMOVE }

	/**
	 * A change to one path. {@code expected} is optional for replacements/removals
	 * and absent for additions;
	 * {@code replacement} is null only for removals.
	 *
	 * @param operation the kind of change
	 * @param expected an optional required previous hash; null retains only the operation's path precondition
	 * @param replacement the resulting hash, or null for a removal
	 */
	public record Change(Operation operation, Hash expected, Hash replacement) {
		public Change {
			Objects.requireNonNull(operation, "operation");
			switch (operation) {
				case ADD -> {
					if (expected != null) {
						throw new IllegalArgumentException("An addition cannot have an expected hash");
					}
					Objects.requireNonNull(replacement, "replacement");
				}
				case REPLACE -> {
					Objects.requireNonNull(replacement, "replacement");
				}
				case REMOVE -> {
					if (replacement != null) {
						throw new IllegalArgumentException("A removal cannot have a replacement hash");
					}
				}
			}
			// Hash is an interface, so retain values rather than potentially mutable callers.
			expected = snapshot(expected);
			replacement = snapshot(replacement);
		}

		private static Hash snapshot(Hash hash) {
			return hash == null ? null : Hash.of(hash.digest(), Objects.requireNonNull(hash.hash(), "hash value"));
		}
	}

	private final String algorithm;
	private final Map<Path, Change> changes;
	private final Set<Path> ignoredPaths;
	private final Set<Path> removedIgnores;

	/**
	 * Constructs a modification, copying and normalizing its path keys.
	 *
	 * @param algorithm the hashing algorithm shared by the base and all changes
	 * @param changes changes indexed by relative file path
	 * @throws IllegalArgumentException if paths, algorithms, or changes conflict
	 */
	public ChrysalisModification(String algorithm, Map<Path, Change> changes) {
		this(algorithm, changes, Set.of(), Set.of());
	}

	/**
	 * Constructs file changes and independent ignore changes. Ignore additions
	 * are idempotent; removals require an explicit ignore in the base snapshot.
	 * No path may be both ignored and have its ignore removed in one layer.
	 */
	public ChrysalisModification(String algorithm, Map<Path, Change> changes,
		Set<Path> ignoredPaths, Set<Path> removedIgnores) {
		Objects.requireNonNull(algorithm, "algorithm");
		try {
			this.algorithm = new BlobFactory(algorithm).algorithm();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalArgumentException("Unsupported hash algorithm: " + algorithm, e);
		}
		Map<Path, Change> copy = new TreeMap<>();
		Objects.requireNonNull(changes, "changes").forEach((path, change) -> {
			Path normalized = normalizePath(path);
			Objects.requireNonNull(change, "change");
			checkAlgorithm(change.expected());
			checkAlgorithm(change.replacement());
			if (copy.putIfAbsent(normalized, change) != null) {
				throw new IllegalArgumentException("Multiple changes for path: " + normalized);
			}
		});
		this.changes = Collections.unmodifiableMap(copy);
		this.ignoredPaths = normalizeRoots(ignoredPaths);
		this.removedIgnores = normalizeRoots(removedIgnores);
		for (Path path : this.ignoredPaths) {
			if (this.removedIgnores.contains(path)) {
				throw new IllegalArgumentException("Cannot ignore and removeIgnore the same path: " + path);
			}
		}
	}

	/** @return immutable paths whose explicit ignores are to be added */
	public Set<Path> ignoredPaths() {
		return ignoredPaths;
	}

	/** @return immutable paths whose inherited explicit ignores are to be removed */
	public Set<Path> removedIgnores() {
		return removedIgnores;
	}

	private static Set<Path> normalizeRoots(Set<Path> paths) {
		Set<Path> copy = new TreeSet<>();
		for (Path path : Objects.requireNonNull(paths, "ignore paths")) {
			if (!copy.add(normalizePath(path))) {
				throw new IllegalArgumentException("Duplicate ignore path: " + path);
			}
		}
		return Collections.unmodifiableSet(copy);
	}

	/** @return the hashing algorithm required by this modification */
	public String algorithm() {
		return algorithm;
	}

	/** @return an immutable map of normalized paths to changes */
	public Map<Path, Change> changes() {
		return changes;
	}

	/**
	 * Applies this modification to a snapshot.
	 *
	 * @param base the unmodified input snapshot
	 * @return a new snapshot with all changes applied
	 * @throws IllegalArgumentException if the algorithm or a precondition does not
	 *         match, or the resulting paths cannot describe a directory of files
	 */
	@SuppressWarnings("deprecation")
	public Chrysalis applyTo(Chrysalis base) {
		Objects.requireNonNull(base, "base");
		if (!algorithm.equalsIgnoreCase(base.getBlobFactory().algorithm())) {
			throw new IllegalArgumentException("Hash algorithm mismatch: expected " + algorithm
				+ ", got " + base.getBlobFactory().algorithm());
		}
		Map<Path, Hash> files = filesByPath(base);
		Set<Path> roots = new HashSet<>(base.getIgnoredPaths());
		for (Path path : removedIgnores) {
			if (!roots.remove(path)) {
				throw new IllegalArgumentException("No explicit inherited ignore for path: " + path);
			}
		}
		roots.addAll(ignoredPaths);
		changes.forEach((path, change) -> {
			Hash actual = files.get(path);
			if (change.operation() == Operation.ADD) {
				if (actual != null) {
					throw new IllegalArgumentException("Cannot add existing path: " + path);
				}
			} else if (actual == null || (change.expected() != null && !change.expected().hashEquals(actual))) {
				throw new IllegalArgumentException("Hash precondition failed for " + path
					+ ": expected " + (change.expected() == null ? "an existing file" : change.expected().hash()) + ", got "
					+ (actual == null ? "absent" : actual.hash()));
			}
			if (change.operation() == Operation.REMOVE) {
				files.remove(path);
			} else {
				files.put(path, change.replacement());
			}
		});
		// A Chrysalis contains files only: no expected file can be another's parent.
		for (Path path : files.keySet()) {
			for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
				if (files.containsKey(parent)) {
					throw new IllegalArgumentException("Expected file is also a directory: " + parent);
				}
			}
		}
		SetMultimap<Hash, Path> blobs = TreeMultimap.create(
			Comparator.comparing(Hash::hash), Comparator.naturalOrder());
		files.forEach((path, hash) -> blobs.put(hash, path));
		Chrysalis result = new Chrysalis();
		result.factory(base.getBlobFactory());
		result.setBlobs(Multimaps.synchronizedSetMultimap(blobs));
		result.setIgnoredPaths(roots);
		return result;
	}

	/**
	 * Computes the changes between two complete file snapshots.
	 * Partial manifests containing only changed files are not valid targets here:
	 * every base path absent from {@code modified} becomes a removal.
	 *
	 * @param base the original complete snapshot
	 * @param modified the desired complete snapshot
	 * @return a modification that produces {@code modified} from {@code base}
	 */
	public static ChrysalisModification between(Chrysalis base, Chrysalis modified) {
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(modified, "modified");
		String algorithm = base.getBlobFactory().algorithm();
		if (!algorithm.equalsIgnoreCase(modified.getBlobFactory().algorithm())) {
			throw new IllegalArgumentException("Cannot diff snapshots with different hash algorithms");
		}
		Map<Path, Hash> before = filesByPath(base);
		Map<Path, Hash> after = filesByPath(modified);
		Builder builder = builder(algorithm);
		for (Path path : base.getIgnoredPaths()) {
			if (!modified.getIgnoredPaths().contains(path)) {
				builder.removeIgnore(path);
			}
		}
		for (Path path : modified.getIgnoredPaths()) {
			if (!base.getIgnoredPaths().contains(path)) {
				builder.ignore(path);
			}
		}
		before.forEach((path, hash) -> {
			Hash replacement = after.get(path);
			if (replacement == null) {
				builder.remove(path, hash);
			} else if (!hash.hashEquals(replacement)) {
				builder.replace(path, hash, replacement);
			}
		});
		after.forEach((path, hash) -> {
			if (!before.containsKey(path)) {
				builder.add(path, hash);
			}
		});
		return builder.build();
	}

	/**
	 * Loads standalone modification data written using {@link Weaver#GSON}.
	 * @deprecated Use {@link Vault#load(com.wildermods.thrixlvault.steam.IVaultable, Path)}
	 * to resolve either declaration type with its owning vault.
	 */
	@Deprecated(forRemoval = false)
	public static ChrysalisModification fromFile(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return Objects.requireNonNull(Weaver.GSON.fromJson(reader, ChrysalisModification.class),
				"Modification file contains null");
		}
	}

	/**
	 * Writes standalone patch data without a parent or tags.
	 * @deprecated Use {@link Vault#saveModification} for named vault contexts.
	 */
	@Deprecated(forRemoval = false)
	public void toFile(Path path) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path)) {
			Weaver.GSON.toJson(this, ChrysalisModification.class, writer);
		}
	}

	/** @return a builder using the specified hashing algorithm */
	public static Builder builder(String algorithm) {
		return new Builder(algorithm);
	}

	private void checkAlgorithm(Hash hash) {
		if (hash != null && !algorithm.equalsIgnoreCase(hash.algorithm())) {
			throw new IllegalArgumentException("Hash uses " + hash.algorithm() + ", expected " + algorithm);
		}
	}

	static Path normalizePath(Path path) {
		Objects.requireNonNull(path, "path");
		Path normalized = path.normalize();
		String text = normalized.toString();
		if (normalized.isAbsolute() || text.isEmpty() || normalized.startsWith("..")
			|| text.matches("^[A-Za-z]:.*")
			|| (path.getFileSystem().getSeparator().equals("/") && text.contains("\\"))) {
			throw new IllegalArgumentException("Expected a relative file path within the artifact: " + path);
		}
		return normalized;
	}

	private static Map<Path, Hash> filesByPath(Chrysalis chrysalis) {
		Map<Path, Hash> files = new HashMap<>();
		SetMultimap<Hash, Path> blobs = chrysalis.blobs();
		synchronized (blobs) {
			blobs.forEach((hash, path) -> {
				Path normalized = normalizePath(path);
				Hash previous = files.putIfAbsent(normalized, hash);
				if (previous != null) {
					throw new IllegalArgumentException("Multiple expectations for path: " + normalized);
				}
			});
		}
		return files;
	}

	/** Builds a modification with one operation per normalized path. */
	public static final class Builder {
		private final String algorithm;
		private final Map<Path, Change> changes = new HashMap<>();
		private final Set<Path> ignoredPaths = new HashSet<>();
		private final Set<Path> removedIgnores = new HashSet<>();

		private Builder(String algorithm) {
			this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
		}

		/** Adds an expectation for a previously absent path. */
		public Builder add(Path path, Hash replacement) {
			return put(path, new Change(Operation.ADD, null, replacement));
		}

		/** Replaces the expectation at a path with the specified original hash. */
		public Builder replace(Path path, Hash expected, Hash replacement) {
			return put(path, new Change(Operation.REPLACE, Objects.requireNonNull(expected, "expected"), replacement));
		}

		/** Replaces an existing path without requiring a particular previous hash. */
		public Builder replace(Path path, Hash replacement) {
			return put(path, new Change(Operation.REPLACE, null, replacement));
		}

		/** Removes the expectation at a path with the specified original hash. */
		public Builder remove(Path path, Hash expected) {
			return put(path, new Change(Operation.REMOVE, Objects.requireNonNull(expected, "expected"), null));
		}

		/** Removes an existing path without requiring a particular previous hash. */
		public Builder remove(Path path) {
			return put(path, new Change(Operation.REMOVE, null, null));
		}

		/** Hides a path and all descendants without discarding stored expectations. */
		public Builder ignore(Path path) {
			Path normalized = normalizePath(path);
			if (removedIgnores.contains(normalized)) {
				throw new IllegalArgumentException("Conflicting ignore operations for " + normalized);
			}
			ignoredPaths.add(normalized);
			return this;
		}

		/**
		 * Removes an exact, inherited ignore root. An ignored ancestor continues to
		 * cover this path; this operation does not create an exception to that root.
		 */
		public Builder removeIgnore(Path path) {
			Path normalized = normalizePath(path);
			if (ignoredPaths.contains(normalized)) {
				throw new IllegalArgumentException("Conflicting ignore operations for " + normalized);
			}
			removedIgnores.add(normalized);
			return this;
		}

		private Builder put(Path path, Change change) {
			Path normalized = normalizePath(path);
			if (changes.putIfAbsent(normalized, change) != null) {
				throw new IllegalArgumentException("Multiple changes for path: " + normalized);
			}
			return this;
		}

		/** @return an immutable copy of the accumulated changes */
		public ChrysalisModification build() {
			return new ChrysalisModification(algorithm, changes, ignoredPaths, removedIgnores);
		}
	}
}
