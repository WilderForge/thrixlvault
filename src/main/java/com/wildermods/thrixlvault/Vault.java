package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.wildermods.masshash.Blob;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.MissingResourceException;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.steam.IVaultable;

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
	
	
	/**
	 * Constructs a {@code Vault} rooted at the given {@code vaultDir}, using the default
	 * blob subdirectory {@code vaultDir/blobs}.
	 * <p>
	 * This constructor is a shorthand for {@code new Vault(vaultDir, Path.of("."))}.
	 * It creates the vault directory and blob directory if they do not already exist.
	 *
	 * @param vaultDir the base directory for the vault
	 * @throws IOException if an I/O error occurs while creating the necessary directories
	 * @throws IllegalArgumentException if the resolved blob directory is outside the vault directory
	 */
	public Vault(Path vaultDir) throws IOException {
		this(vaultDir, Path.of("."));
	}
	
	/**
	 * Constructs a {@code Vault} with the specified vault directory and a blob directory
	 * relative to it.
	 * <p>
	 * The blob directory must be a relative path. It will be resolved as {@code vaultDir.resolve(blobDir).resolve("blobs")},
	 * and both the vault directory and the final blob directory will be created if they do not already exist.
	 * <p>
	 * If the resolved blob directory is outside the vault directory (e.g., via a path like {@code "../"}), an exception will be thrown.
	 *
	 * @param vaultDir the root directory of the vault
	 * @param blobDir a relative path (which will be resolved against {@code vaultDir}) where blobs will be stored. {@code /blobs} will be appended to the path.
	 * @throws IOException if an I/O error occurs while creating the necessary directories
	 * @throws IllegalArgumentException if {@code blobDir} is absolute or if the resolved blob directory escapes {@code vaultDir}
	 */
	public Vault(Path vaultDir, Path blobDir) throws IOException {
		if(blobDir.isAbsolute()) {
			throw new IllegalArgumentException("Blob directory must be relative!");
		}
		
		this.vaultDir = vaultDir;
		this.blobDir = vaultDir.resolve(blobDir).resolve("blobs");
		
		if (!this.blobDir.normalize().startsWith(this.vaultDir)) {
			throw new IllegalArgumentException("Blob directory must be within the vault directory!");
		}
		
		Files.createDirectories(this.vaultDir);
		Files.createDirectories(this.blobDir);
	}
	
	public ChrysalisizedVault chrysalisize(IVaultable version) throws IOException, MissingVersionException {
		return new ChrysalisizedVault(version, this);
	}
	

	public ChrysalisizedVault chrysalisize(IVaultable version, Chrysalis chrysalis) throws IOException {
		return new ChrysalisizedVault(version, this, chrysalis);
	}
	
	public Path getVaultDir() {
		return vaultDir;
	}
	
	public Path getBlobDir() {
		return blobDir;
	}
	
	public boolean hasBlob(Hash hash) {
		return Files.exists(getBlobFile(hash));
	}
	
	public Blob getBlob(Hash hash) throws IOException, IntegrityException {
		Path path = getBlobFile(hash);
		if(Files.exists(path)){
			return new Blob(path, hash);
		}
		throw new MissingResourceException(path.toString());
	}
	
	public boolean hasChrysalis(IVaultable artifact) {
		return Files.exists(getChrysalisFile(artifact));
	}

	public Path getChrysalisFile(IVaultable artifact) {
		return vaultDir.resolve(artifact.artifactPath()).resolve("blobs.json");
	}
	
	public Path getBlobFile(Hash hash) {
		return getBlobFile(blobDir, hash);
	}
	
	public Path getBlobFile(Path blobDir, Hash hash) {
		return blobDir.resolve(hash.hash());
	}
	
	@Override
	public String toString() {
		return "Vault " + vaultDir;
	}
	
}
