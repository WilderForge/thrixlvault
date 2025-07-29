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
	
	public Vault(Path vaultDir) throws IOException {
		this.vaultDir = vaultDir;
		this.blobDir = vaultDir.resolve("blobs");
		
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
