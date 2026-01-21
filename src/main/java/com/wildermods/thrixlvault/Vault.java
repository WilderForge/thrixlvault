package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
	
	public Path getVaultDir() {
		return vaultDir;
	}
	
	public Path getBlobDir() {
		return blobDir;
	}
	
	public boolean hasChrysalis(IVaultable artifact) {
		return Files.exists(getChrysalisFile(artifact));
	}

	public Path getChrysalisFile(IVaultable artifact) {
		return vaultDir.resolve(artifact.artifactPath()).resolve("blobs.json");
	}
	
	@Override
	public String toString() {
		return "Vault " + vaultDir;
	}
	
}
