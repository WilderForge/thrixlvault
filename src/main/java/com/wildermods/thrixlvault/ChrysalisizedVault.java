package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
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

import com.wildermods.thrixlvault.exception.DatabaseError;
import com.wildermods.thrixlvault.exception.DatabaseIntegrityError;
import com.wildermods.thrixlvault.exception.DatabaseMissingBlobError;
import com.wildermods.thrixlvault.exception.MissingResourceException;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.steam.IVersioned;

public class ChrysalisizedVault extends Vault implements IVersioned {

	static final Logger LOGGER = LogManager.getLogger();
	
	final IVersioned version;
	final Chrysalis chrysalis;
	final Marker marker;
	
	ChrysalisizedVault(IVersioned version, Vault parent) throws IOException {
		this(version, parent, Chrysalis.fromFile(parent.getChrysalisFile(version)));
	}
	
	ChrysalisizedVault(IVersioned version, Vault parent, Chrysalis chrysalis) throws IOException {
		super(parent.vaultDir, parent.versionDir);
		this.chrysalis = chrysalis;
		this.version = version;
		this.marker = MarkerManager.getMarker(version());
	}

	public Chrysalis getChrysalis() {
		return chrysalis;
	}
	
	public void verifyBlobs() throws InterruptedException, ExecutionException {
		verifyBlobs(false);
	}
	
	@Deprecated(forRemoval = true)
	public void verifyBlobs(boolean unused) throws InterruptedException, ExecutionException {
		LOGGER.info(marker, "Verifying " + version);
		final SetMultimap<Hash, Throwable> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());

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
				problems.put(hash, err);
			}
		});

		if(problems.size() == 0) {
			LOGGER.info(marker, "Database Verification successful. All " + chrysalis.blobs().keys().elementSet().size() + " blobs are present and valid.");
		}
		else {
			String message = "Database Verification Failed";
			DatabaseIntegrityError e = new DatabaseIntegrityError(message, problems.values().toArray(new Throwable[]{}));
			for(Throwable problem : problems.values()) {
				if(!(problem instanceof DatabaseError) && !(problem instanceof IntegrityException))
				e.addSuppressed(problem);
			}
			throw e;
		}
	}
	
	public void verifyDirectory(Path path) throws InterruptedException, IntegrityException, ExecutionException {
		verifyDirectory(path, true);
	}
	
	public void verifyDirectory(Path path, boolean verifyDatabase) throws InterruptedException, IntegrityException, ExecutionException {
		final SetMultimap<Hash, Throwable> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());
		
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
					problems.put(hash, t);
				}
			}
		});
		
		if(problems.size() == 0) {
			LOGGER.info(marker, "Verification successful. All " + chrysalis.blobs().values().size() + " expected resources are present and valid.");
		}
		else {
			String message = "Verification Failed";
			IntegrityException e = new IntegrityException(message, problems.values().toArray(new Throwable[]{}));
			for(Throwable problem : problems.values()) {
				e.addSuppressed(problem);
			}
			throw e;
		}
	}
	
	
	boolean terminated = false;
	
	public void computeOverBlobs(HashTask hashTask) throws InterruptedException, ExecutionException {
	    Multiset<Hash> hashes = chrysalis.blobs().keys();
	    int threads = Runtime.getRuntime().availableProcessors();
	    ExecutorService executor = Executors.newFixedThreadPool(threads);
	    List<Future<Void>> futures = new ArrayList<>();

	    terminated = false;
	    
	    try {
	        for (Hash hash : hashes.elementSet()) {
	            Future<Void> future = executor.submit(() -> {
	            	if(!terminated) {
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
	            	terminated = true;
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
	
	public SetMultimap<Hash, Throwable> delete() throws IOException, MissingVersionException {
		
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

	@Override
	public String version() {
		return version.version();
	}
	
}
