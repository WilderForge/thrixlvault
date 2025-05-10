package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.wildermods.masshash.Blob;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.MissingResourceException;
import com.wildermods.thrixlvault.steam.IVersioned;
import com.wildermods.thrixlvault.utils.OS;

public class Vault {

	public static final Path DEFAULT_VAULT_DIR = Path.of(System.getProperty("user.home")).resolve("thrixlvault");
	public static final Path DEFAULT_BLOB_DIR = DEFAULT_VAULT_DIR.resolve("blobs");
	
	public static final Vault DEFAULT;
	static {
		try {
			DEFAULT = new Vault(DEFAULT_VAULT_DIR);
		} catch (IOException e) {
			throw new Error(e);
		}
	}
	
	public final Path vaultDir;
	public final Path versionDir;
	public final Path blobDir;
	
	public Vault(Path vaultDir) throws IOException {
		this(vaultDir, OS.getOS());
	}
	
	public Vault(Path vaultDir, OS os) throws IOException {
		this(vaultDir, vaultDir.resolve(os.name()));
	}
	
	public Vault(Path vaultDir, Path versionDir) throws IOException {
		this.vaultDir = vaultDir;
		this.versionDir = versionDir;
		this.blobDir = vaultDir.resolve("blobs");
		
		Files.createDirectories(this.vaultDir);
		Files.createDirectories(this.versionDir);
		Files.createDirectories(this.blobDir);
	}
	
	public ChrysalisizedVault chrysalisize(IVersioned version) throws IOException {
		return new ChrysalisizedVault(version, this);
	}
	
	public Path getVaultDir() {
		return vaultDir;
	}
	
	public Path getVersionDir() {
		return versionDir;
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
	
	public boolean hasChrysalis(IVersioned versioned) {
		return Files.exists(getChrysalisFile(versioned));
	}

	public Path getChrysalisFile(IVersioned versioned) {
		return versionDir.resolve(versioned.version()).resolve("blobs.json");
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
