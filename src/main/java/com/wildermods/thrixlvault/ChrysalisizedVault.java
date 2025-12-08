package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Multiset;
import com.google.common.collect.SetMultimap;
import com.wildermods.masshash.Blob;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.exception.IntegrityProblem;
import com.wildermods.thrixlvault.exception.DatabaseError;
import com.wildermods.thrixlvault.exception.DatabaseError.DatabaseProblem;
import com.wildermods.thrixlvault.exception.DatabaseIntegrityError;
import com.wildermods.thrixlvault.exception.DatabaseMissingBlobError;
import com.wildermods.thrixlvault.exception.MissingResourceException;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.exception.UnknownVersionException;
import com.wildermods.thrixlvault.steam.IVaultable;

public class ChrysalisizedVault extends Vault implements IVaultable {

	static final Logger LOGGER = LogManager.getLogger();
	
	final IVaultable artifact;
	final Chrysalis chrysalis;
	final Marker marker;
	
	ChrysalisizedVault(IVaultable artifact, Vault parent) throws IOException, MissingVersionException {
		this(artifact, parent, handleFromFile(artifact, parent));
	}
	
	ChrysalisizedVault(IVaultable artifact, Vault parent, Chrysalis chrysalis) throws IOException {
		super(parent.vaultDir);
		this.chrysalis = chrysalis;
		this.artifact = artifact;
		this.marker = MarkerManager.getMarker(artifact.name());
	}

	public Chrysalis getChrysalis() {
		return chrysalis;
	}
	
	public void verifyBlobs() throws InterruptedException, ExecutionException {
		LOGGER.info(marker, "Verifying " + artifact);
		final SetMultimap<Hash, IntegrityProblem> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());

		computeOverBlobs((hash, vaultDir, chrysalis) -> {
			Path blobFile = vaultDir.resolve(hash.hash());
			try {
				if (!Files.exists(blobFile)) {
					String msg = "Missing blob - " + hash + " (" + blobFile + ")";
					DatabaseMissingBlobError err = new DatabaseMissingBlobError(msg);
					throw err;
				}
				
				try {
					new Blob(blobFile, hash); //constructor verifies the file contents match the hash
				}
				catch(IntegrityException e) {
					throw new DatabaseIntegrityError("Corrupted blob - " + e.getMessage());
				}
			}
			catch (Throwable t) {
				String msg = "Failed to read blob " + hash + "due to: " + t.getMessage();
				DatabaseError err;
				if(t instanceof DatabaseError) {
					err = (DatabaseError) t;
				}
				else {
					err = new DatabaseError(msg, t);
				}
				problems.put(hash, DatabaseProblem.fromThrown(err));
			}
		});

		if(problems.size() == 0) {
			LOGGER.info(marker, "Database Verification successful. All " + chrysalis.blobs().keys().elementSet().size() + " blobs are present and valid.");
		}
		else {
			String message = "Database Verification Failed";
			DatabaseIntegrityError e = new DatabaseIntegrityError(message, problems.values().toArray(new IntegrityProblem[]{}));
			throw e;
		}
	}
	
	public void verifyDirectory(Path path) throws InterruptedException, IntegrityException, ExecutionException {
		verifyDirectory(path, true);
	}
	
	public void verifyDirectory(Path path, boolean verifyDatabase) throws InterruptedException, IntegrityException, ExecutionException {
		final SetMultimap<Hash, IntegrityProblem> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());
		
		LOGGER.info(marker, "Verifying " + path);
		
		if(verifyDatabase) {
			verifyBlobs();
		}
		else {
			LOGGER.warn(marker, "Skipping database verification.");
		}
		computeOverBlobs((hash, vaultDir, chrysalis) -> {
			Set<Path> resources = chrysalis.blobs().get(hash);
			for(Path localizedResource : resources) {
				try {
					Path resource = path.resolve(localizedResource);
					if(!Files.exists(resource)) {
						throw new MissingResourceException("Missing Resource - " + hash + " (" + resource + ")");
					}
					new Blob(resource, hash); //constructor verifies the file contents match the hash
				}
				catch(Throwable t) {
					if(t instanceof IntegrityException) {
						if(t.getClass() == IntegrityException.class) {
							t = new IntegrityException("Failed to verify resource " + localizedResource + ". Reason: " + t.getMessage());
						}
					}
					else {
						t = new IntegrityException("Failed to verify resource " + localizedResource + ". Reason: " + t.getMessage() + ". (" + hash + ")", t);
					}
					problems.put(hash, IntegrityProblem.fromThrown(t));
				}
			}
		});
		
		if(problems.size() == 0) {
			LOGGER.info(marker, "Verification successful. All " + chrysalis.blobs().values().size() + " expected resources are present and valid.");
		}
		else {
			String message = "Verification Failed";
			IntegrityException e = new IntegrityException(message, problems.values().toArray(new IntegrityProblem[]{}));
			throw e;
		}
	}
	
	public void computeOverBlobs(HashTask hashTask) throws InterruptedException, ExecutionException {
	    Multiset<Hash> hashes = chrysalis.blobs().keys();
	    int threads = Runtime.getRuntime().availableProcessors();
	    ExecutorService executor = Executors.newFixedThreadPool(threads);
	    List<Future<Void>> futures = new ArrayList<>();

	    boolean[] terminated = new boolean[] {false};
	    
	    try {
	        for (Hash hash : hashes.elementSet()) {
	            Future<Void> future = executor.submit(() -> {
	            	if(!terminated[0]) {
	            		hashTask.call(hash, blobDir, chrysalis);
	            	}
	                return null;
	            });
	            futures.add(future);
	        }

	        for (Future<Void> future : futures) {
	            try {
	                future.get(); // this will throw if the task failed
	            } catch (ExecutionException e) {
	            	terminated[0] = true;
	                // Cancel all other tasks
	                for (Future<Void> f : futures) {
	                    f.cancel(true);
	                }
	                // Shut down and rethrow
	                executor.shutdownNow();
	                throw e;
	            }
	        }
	    }
	    finally {
	        executor.shutdownNow();
	    }
	}

	
	public void export(Path destDir, boolean verifyBlobs) throws InterruptedException, IntegrityException, ExecutionException {
		if(verifyBlobs) {
			verifyBlobs();
		}
		computeOverBlobs((hash, path, chrysalis) -> {
			Blob blob = new Blob(path.resolve(hash.hash()), hash);
			Set<Path> dests = chrysalis.blobs().get(hash);
			for(Path relativeDest : dests) {
				Path dest = destDir.resolve(relativeDest);
				Files.createDirectories(dest.getParent());
				Files.write(dest, blob.data(), StandardOpenOption.CREATE_NEW);
			}
		});
		verifyDirectory(destDir, false);
	}
	
	/**
	 * @deprecated This method permanently deletes all blob files this
	 * version uses from the vault, and as such can corrupt the blob store.
	 *<p>
	 * Deleting a blob is unsafe because multiple game versions may reference
	 * the same blob content. If a shared blob is removed, any version that
	 * depends on it **will** fail verification with a {@link DatabaseMissingBlobError}.
	 * <p>
	 * This method should only be used when the vault contains exactly one
	 * version, or when the entire vault is being deleted (e.g., when removing
	 * temporary vaults used for unit tests). Under all other circumstances,
	 * blobs must be preserved, and this method should not be called.
	 */
	@Deprecated(forRemoval = false)
	public SetMultimap<Hash, Throwable> purge() throws IOException, UnknownVersionException {
		
		Multiset<Hash> hashes = chrysalis.blobs().keys();
		final SetMultimap<Hash, Throwable> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());

		int threads = Runtime.getRuntime().availableProcessors();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		List<Callable<Void>> tasks = new ArrayList<>();
		for (Hash hash : hashes.elementSet()) {
			Path blobPath = getBlobFile(hash);

			tasks.add(() -> {
				Files.deleteIfExists(blobPath);
				return null;
			});
		}
		
		Files.delete(getChrysalisFile());
		
		try {
			executor.invokeAll(tasks);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Deletion interrupted", e);
		} finally {
			executor.shutdown();
		}
		return problems;
	}
	
	public boolean hasChrysalis() {
		return hasChrysalis(this);
	}
	
	public Path getChrysalisFile() {
		return getChrysalisFile(this);
	}
	
	private static final Chrysalis handleFromFile(IVaultable version, Vault vault) throws IOException, MissingVersionException {
		try {
			return Chrysalis.fromFile(vault.getChrysalisFile(version));
		}
		catch(NoSuchFileException e) {
			throw new MissingVersionException(version.toString(), e);
		}
	}
	
	public IVaultable getArtifact() {
		return artifact;
	}

	@Override
	public String name() {
		return artifact.name();
	}

	@Override
	public Path artifactPath() {
		return artifact.artifactPath();
	}
	
}
