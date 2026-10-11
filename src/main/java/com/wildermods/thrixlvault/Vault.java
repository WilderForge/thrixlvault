package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.wildermods.masshash.BlobFactory;
import com.wildermods.thrixlvault.exception.DatabaseVaultCircularityError;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.steam.IVaultable;

/**
 * Represents a ThrixlVault storage location. A vault holds blobs and
 * version-specific Chrysalis data for one or more game versions.
 *
 * Vaults store blob files in a shared <code>blobs</code> directory, and each
 * version stores its generated Chrysalis data under its own artifact path.
 *
 * Blobs are intended to be immutable and shared across all versions that
 * reference them.
 */
public class Vault {

	public static final Path DEFAULT_VAULT_DIR = Path.of(System.getProperty("user.home")).resolve("thrixlvault");
	public static final Path DEFAULT_BLOB_DIR = DEFAULT_VAULT_DIR.resolve("blobs");
	
	public static final Vault DEFAULT;
	static {
		try {
			DEFAULT = new Vault(DEFAULT_VAULT_DIR);
		}
		catch(Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}
	
	public final Path vaultDir;
	public final Path blobDir;
	
	public Vault(Path vaultDir) throws IOException {
		this.vaultDir = vaultDir;
		this.blobDir = vaultDir.resolve("blobs");
		
		Files.createDirectories(this.vaultDir);
		Files.createDirectories(this.blobDir);
	}
	
	public ChrysalisizedVault chrysalisize(IVaultable version) throws IOException, MissingVersionException {
		return new ChrysalisizedVault(version, this);
	}
	
	public ChrysalisizedVault chrysalisize(IVaultable version, String name) throws IOException, MissingVersionException {
		return new ChrysalisizedVault(version, this, name);
	}

	/** Loads only the local declaration; does not resolve or verify its parent. */
	VaultContext readDeclaration(IVaultable artifact, String name) throws IOException, MissingVersionException {
		Path file = getChrysalisFile(artifact, name);
		try (var reader = Files.newBufferedReader(file)) {
			VaultContext context = Objects.requireNonNull(Weaver.GSON.fromJson(reader, VaultContext.class),
				"Context contains null: " + file);
			checkDefault(name, context);
			return context;
		} catch (NoSuchFileException e) {
			throw new MissingVersionException(artifact + " context '" + name + "' at " + file, e);
		}
	}

	/** Saves a named modification without replacing an existing declaration. */
	public ChrysalisizedVault saveModification(IVaultable artifact, String name, String parent,
		ChrysalisModification modification) throws IOException, MissingVersionException {
		return saveModification(artifact, name, parent, modification, Set.of(), Set.of());
	}

	/** Saves a modification with locally declared tag additions and removals. */
	public ChrysalisizedVault saveModification(IVaultable artifact, String name, String parent,
		ChrysalisModification modification, Set<String> tags, Set<String> removeTags)
		throws IOException, MissingVersionException {
		return saveContext(artifact, name, VaultContext.modification(parent, modification, tags, removeTags));
	}

	/** Saves a full snapshot and its tags without replacing an existing declaration. */
	public ChrysalisizedVault saveSnapshot(IVaultable artifact, String name, Chrysalis snapshot,
		Set<String> tags) throws IOException, MissingVersionException {
		return saveContext(artifact, name, VaultContext.snapshot(snapshot, tags));
	}

	/**
	 * Validates and saves a local declaration within this vault. Inheritance is
	 * resolved before writing, so invalid parents, cycles, and hash preconditions
	 * do not overwrite the previous file. No blob bytes are read or generated.
	 */
	ChrysalisizedVault saveContext(IVaultable artifact, String name, VaultContext context)
		throws IOException, MissingVersionException {
		return saveContext(artifact, name, context, false);
	}

	/** @param replaceExisting whether an existing declaration may be overwritten */
	ChrysalisizedVault saveContext(IVaultable artifact, String name, VaultContext context,
		boolean replaceExisting) throws IOException, MissingVersionException {
		requireContextName(name);
		Objects.requireNonNull(context, "context");
		ResolvedContext resolved = resolveContext(artifact, name, context);
		String json = Weaver.GSON.toJson(context, VaultContext.class);
		Path file = getChrysalisFile(artifact, name);
		Files.createDirectories(file.getParent());
		if (replaceExisting) {
			Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} else {
			Files.writeString(file, json, StandardOpenOption.CREATE_NEW);
		}
		return new ChrysalisizedVault(artifact, this, name, resolved);
	}

	/** Loads a named context, resolving snapshots and modifications uniformly. */
	public ChrysalisizedVault loadContext(IVaultable artifact, String name) throws IOException, MissingVersionException {
		return chrysalisize(artifact, name);
	}

	/**
	 * Loads any supported JSON declaration using this vault for blobs and named parents.
	 * An external file is not registered or saved in the vault by loading it.
	 */
	public ChrysalisizedVault load(IVaultable artifact, Path file) throws IOException, MissingVersionException {
		Path source = file.toAbsolutePath().normalize();
		String filename = source.getFileName().toString();
		String name = filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
		boolean named = source.getParent().equals(getChrysalisFile(artifact).toAbsolutePath().normalize().getParent())
			&& filename.endsWith(".json");
		if (named) return chrysalisize(artifact, name);
		VaultContext declaration;
		try (var reader = Files.newBufferedReader(source)) {
			declaration = Objects.requireNonNull(Weaver.GSON.fromJson(reader, VaultContext.class), "Null declaration: " + source);
		}
		return new ChrysalisizedVault(artifact, this, name,
			resolveContext(artifact, name, declaration, source, false));
	}

	public ChrysalisizedVault saveModification(IVaultable artifact, String name, String parent,
		ChrysalisModification modification, Set<String> tags, Set<String> removeTags, boolean replaceExisting)
		throws IOException, MissingVersionException {
		return saveContext(artifact, name, VaultContext.modification(parent, modification, tags, removeTags), replaceExisting);
	}

	public ChrysalisizedVault saveSnapshot(IVaultable artifact, String name, Chrysalis snapshot,
		Set<String> tags, boolean replaceExisting) throws IOException, MissingVersionException {
		return saveContext(artifact, name, VaultContext.snapshot(snapshot, tags), replaceExisting);
	}

	void validateContext(IVaultable artifact, String name, VaultContext declaration) throws IOException, MissingVersionException {
		resolveContext(artifact, name, declaration);
	}

	ResolvedContext resolveContext(IVaultable artifact, String name) throws IOException, MissingVersionException {
		return resolveContext(artifact, name, null);
	}

	private ResolvedContext resolveContext(IVaultable artifact, String name, VaultContext proposed)
		throws IOException, MissingVersionException {
		return resolveContext(artifact, requireContextName(name), proposed, getChrysalisFile(artifact, name), true);
	}

	private ResolvedContext resolveContext(IVaultable artifact, String name, VaultContext proposed, Path source, boolean named)
		throws IOException, MissingVersionException {
		// Use source identities: an external file may share a name with a vault parent.
		var layers = new ArrayList<ContextLayer>();
		List<Path> seen = new ArrayList<>();
		String current = name;
		Path currentFile = source;
		boolean currentNamed = named;
		while (current != null) {
			if (!seen.add(currentFile.toAbsolutePath().normalize())) {
				String[] cycle = new String[seen.size() + 1];
				for(int i = 0; i < seen.size(); i++) {
					cycle[i] = seen.get(i).toAbsolutePath().normalize().getFileName().toString();
				}
				cycle[cycle.length - 1] = currentFile.toAbsolutePath().normalize().getFileName().toString();
				throw new DatabaseVaultCircularityError(this, cycle);
			}
			VaultContext declaration = layers.isEmpty() && proposed != null ? proposed : readDeclaration(artifact, current);
			if (currentNamed) checkDefault(current, declaration);
			layers.add(new ContextLayer(current, currentFile, currentNamed, declaration));
			current = declaration.parent();
			if (current != null) currentFile = getChrysalisFile(artifact, current);
			currentNamed = true;
		}
		Collections.reverse(layers);
		Chrysalis result = null;
		Set<String> tags = new HashSet<>();
		ChrysalisizedVault parent = null;
		ResolvedContext resolved = null;
		for (int index = 0; index < layers.size(); index++) {
			ContextLayer layer = layers.get(index);
			VaultContext declaration = layer.declaration();
			if (declaration.isSnapshot()) {
				result = declaration.snapshot().orElseThrow();
			} else {
				ChrysalisModification modification = declaration.modification().orElseThrow();
				if (result == null) {
					try {
						result = Chrysalis.EMPTY.clone().factory(new BlobFactory(modification.algorithm()));
					} catch (java.security.NoSuchAlgorithmException e) {
						throw new IOException(e);
					}
				}
				result = modification.applyTo(result);
			}
			tags.removeAll(declaration.removeTags());
			tags.addAll(declaration.tags());
			resolved = new ResolvedContext(result, Set.copyOf(tags), declaration, parent, layer.file(), layer.named());
			if (index < layers.size() - 1) parent = new ChrysalisizedVault(artifact, this, layer.name(), resolved);
		}
		return resolved;
	}

	private record ContextLayer(String name, Path file, boolean named, VaultContext declaration) {}

	private static void checkDefault(String name, VaultContext context) {
		if (name.equals("blobs") && !context.isSnapshot()) {
			throw new IllegalArgumentException("The default 'blobs' context must be a full snapshot");
		}
	}

	static String requireContextName(String name) {
		Objects.requireNonNull(name, "context name");
		if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/")
			|| name.contains("\\") || name.contains(":")) {
			throw new IllegalArgumentException("Expected a context name without path components: " + name);
		}
		return name;
	}

	record ResolvedContext(Chrysalis chrysalis, Set<String> tags, VaultContext declaration,
		ChrysalisizedVault parent, Path source, boolean named) {}
	
	public Path getVaultDir() {
		return vaultDir;
	}
	
	public Path getBlobDir() {
		return blobDir;
	}
	
	public boolean hasChrysalis(IVaultable artifact) {
		return hasChrysalis(artifact, "blobs");
	}
	
	public boolean hasChrysalis(IVaultable artifact, String name) {
		return Files.exists(getChrysalisFile(artifact, name));
	}

	public Path getChrysalisFile(IVaultable artifact) {
		return getChrysalisFile(artifact, "blobs");
	}
	
	public Path getChrysalisFile(IVaultable artifact, String name) {
		requireContextName(name);
		return vaultDir.resolve(artifact.artifactPath()).resolve(name + ".json");
	}
	
	@Override
	public String toString() {
		return "Vault " + vaultDir;
	}
	
}
