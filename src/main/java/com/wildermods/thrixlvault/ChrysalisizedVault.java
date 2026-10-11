package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import com.wildermods.masshash.BlobFactory;
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

/**
 * Represents an artifact stored in a {@link Vault} along with its computed {@link Chrysalis}.
 *
 * <p>
 * A {@code ChrysalisizedVault} allows verification of the integrity of all blobs and resources
 * associated with a particular artifact. It supports concurrent verification and export operations,
 * and it ensures that resources are consistent with their expected content.
 * </p>
 *
 * <p>
 * This class wraps an {@link IVaultable} artifact and provides access to its {@link Chrysalis},
 * which maps {@link Hash} objects to the corresponding file paths that share the same hash.
 * </p>
 *
 * <p>
 * Most operations that read or verify blobs are thread-safe. Export and deletion operations
 * are also designed to work safely with concurrent verification tasks, but care must be taken
 * with the deprecated {@link #purge()} method, which can permanently remove blobs and corrupt
 * the vault if multiple versions share the same blob.
 * </p>
 */
public class ChrysalisizedVault extends Vault implements IVaultable {

	static final Logger LOGGER = LogManager.getLogger();
	
	final IVaultable artifact;
	final Chrysalis chrysalis;
	final String name;
	final Marker marker;
	private final VaultContext context;
	private final Set<String> tags;
	private final Vault owningVault;
	private final ChrysalisizedVault inheritedParent;
	private final Path sourceFile;
	private final boolean namedContext;
	
	
	/**
	 * Constructs a ChrysalisizedVault for the given artifact by loading its Chrysalis
	 * from the parent vault.
	 *
	 * @param artifact the versioned artifact
	 * @param parent the vault containing the artifact
	 * @throws IOException if an I/O error occurs while reading the chrysalis
	 * @throws MissingVersionException if the chrysalis file for the artifact is missing
	 */
	protected ChrysalisizedVault(IVaultable artifact, Vault parent) throws IOException, MissingVersionException {
		this(artifact, parent, "blobs");
	}
	
	/**
	 * Constructs a ChrysalisizedVault for the given artifact by loading its Chrysalis
	 * from the parent vault.
	 *
	 * @param artifact the versioned artifact
	 * @param parent the vault containing the artifact
	 * @throws IOException if an I/O error occurs while reading the chrysalis
	 * @throws MissingVersionException if the chrysalis file for the artifact is missing
	 */
	protected ChrysalisizedVault(IVaultable artifact, Vault parent, String name) throws IOException, MissingVersionException {
		this(artifact, parent, name, parent.resolveContext(artifact, name));
	}
	
	/**
	 * Constructs a ChrysalisizedVault with a provided Chrysalis instance.
	 *
	 * @param artifact the versioned artifact
	 * @param parent the vault containing the artifact
	 * @param chrysalis the pre-computed chrysalis for the artifact
	 * @throws IOException if an I/O error occurs while initializing the vault
	 */
	protected ChrysalisizedVault(IVaultable artifact, Vault parent, Chrysalis chrysalis) throws IOException {
		this(artifact, parent, "blobs", chrysalis);
	}
	
	protected ChrysalisizedVault(IVaultable artifact, Vault parent, String name, Chrysalis chrysalis) throws IOException {
		this(artifact, parent, name, chrysalis, Set.of(), null, null, null, false);
	}

	ChrysalisizedVault(IVaultable artifact, Vault parent, String name, Vault.ResolvedContext resolved) throws IOException {
		this(artifact, parent, name, resolved.chrysalis(), resolved.tags(), resolved.declaration(),
			resolved.parent(), resolved.source(), resolved.named());
	}

	private ChrysalisizedVault(IVaultable artifact, Vault parent, String name, Chrysalis chrysalis,
		Set<String> tags, VaultContext context, ChrysalisizedVault inheritedParent, Path sourceFile, boolean namedContext) throws IOException {
		super(parent.vaultDir);
		this.chrysalis = Objects.requireNonNull(chrysalis, "chrysalis");
		this.name = name;
		this.artifact = artifact;
		this.marker = MarkerManager.getMarker(artifact.name());
		this.tags = Set.copyOf(tags);
		this.context = context;
		this.inheritedParent = inheritedParent;
		this.sourceFile = sourceFile;
		this.namedContext = namedContext;
		this.owningVault = parent instanceof ChrysalisizedVault view ? view.getVault() : parent;
	}

	/** @return the owning vault, shared by all named and temporary views */
	public Vault getVault() { return owningVault; }

	/** @return resolved tags, including inherited additions and removals */
	public Set<String> getTags() { return tags; }
	/** @return the local declaration, absent for temporary in-memory compositions */
	Optional<VaultContext> getContext() { return Optional.ofNullable(context); }
	/** @return the selected context name, without its JSON extension */
	public String getContextName() { return name; }

	/** Returns tags added by this declaration, excluding inherited tags. */
	public Set<String> getLocalTags() { return context == null ? Set.of() : context.tags(); }
	/** Returns tag removals explicitly declared in this layer. */
	public Set<String> getRemovedTags() { return context == null ? Set.of() : context.removeTags(); }

	/** Returns the immediate resolved parent, absent for a parentless declaration. */
	public Optional<ChrysalisizedVault> getParent() { return Optional.ofNullable(inheritedParent); }

	/** Returns the oldest ancestor, or this instance when it has no parent. */
	public ChrysalisizedVault getRoot() {
		ChrysalisizedVault root = this;
		while (root.inheritedParent != null) root = root.inheritedParent;
		return root;
	}

	/** Loads an existing named context whose immediate declared parent is this context. */
	public ChrysalisizedVault getChild(String name) throws IOException, MissingVersionException {
		if (!namedContext) throw new IllegalStateException("Only a named vault context can load a child");
		ChrysalisizedVault child = owningVault.chrysalisize(artifact, name);
		// Loading through this owner and artifact fixes the directory; compare local names.
		if (!this.name.equals(child.context.parent())) {
			throw new IllegalArgumentException("Context '" + name + "' is not an immediate child of '" + this.name + "'");
		}
		return child;
	}

	/** Saves the local declaration under a vault name, preserving inheritance and local tags. */
	public ChrysalisizedVault saveAs(String name) throws IOException, MissingVersionException {
		return saveAs(name, false);
	}

	public ChrysalisizedVault saveAs(String name, boolean replaceExisting) throws IOException, MissingVersionException {
		if (context == null) throw new IllegalStateException("A temporary composition has no local declaration; use saveSnapshot instead");
		return owningVault.saveContext(artifact, name, context, replaceExisting);
	}

	/**
	 * Returns the {@link Chrysalis} representing this artifact's hashed file state.
	 *
	 * @return the chrysalis for this artifact
	 */
	public Chrysalis getChrysalis() {
		return chrysalis;
	}

	/**
	 * Returns a new vault view with the supplied modifications applied in order.
	 * The original snapshot and stored manifests are left unchanged; the returned
	 * view uses the same content-addressed blob directory.
	 * <p>
	 * This method does not persist the resolved snapshot or transform blob bytes.
	 * The name identifies the resolved manifest if the caller later saves it.
	 * Export to a fresh directory to obtain exactly the resolved file set; existing
	 * directory verification checks required files but does not reject extra files.
	 *
	 * @param name the name for the resolved snapshot, without the JSON extension
	 * @param modifications modifications to apply in their supplied order
	 * @return a new view of the resolved snapshot
	 * @throws IOException if initializing the vault view fails
	 * @throws IllegalArgumentException if a modification cannot be applied
	 */
	public ChrysalisizedVault withModifications(String name, ChrysalisModification... modifications) throws IOException {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(modifications, "modifications");
		Chrysalis resolved = modifications.length == 0 ? chrysalis.clone() : chrysalis;
		for (ChrysalisModification modification : modifications) {
			resolved = Objects.requireNonNull(modification, "modification").applyTo(resolved);
		}
		return new ChrysalisizedVault(artifact, this, name, resolved, tags, null, this, null, false);
	}
	
	/**
	 * Verifies that all blobs in this artifact exist in the vault and are uncorrupted.
	 *
	 * @throws InterruptedException if the verification is interrupted
	 * @throws ExecutionException if a verification task fails
	 * @throws DatabaseIntegrityError if any blob is missing or corrupted
	 */
	public void verifyBlobs() throws InterruptedException, ExecutionException {
		LOGGER.info(marker, "Verifying " + artifact);
		final SetMultimap<Hash, IntegrityProblem> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());

		final BlobFactory factory = chrysalis.getBlobFactory();
		computeOverBlobs((hash, vaultDir, chrysalis, handler) -> {
			Path blobFile = vaultDir.resolve(hash.hash());
			try {
				if (!Files.exists(blobFile)) {
					String msg = "Missing blob - " + hash + " (" + blobFile + ")";
					DatabaseMissingBlobError err = new DatabaseMissingBlobError(msg);
					throw err;
				}
				
				try {
					BlobIO.verify(factory, blobFile, hash);
				}
				catch(IntegrityException e) {
					throw new DatabaseIntegrityError("Corrupted blob - " + e.getMessage(), e);
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
			LOGGER.info(marker, "Database Verification successful. All " + chrysalis.activeBlobs().keySet().size() + " active blobs are present and valid.");
		}
		else {
			String message = "Database Verification Failed";
			DatabaseIntegrityError e = new DatabaseIntegrityError(message, problems.values().toArray(new IntegrityProblem[]{}));
			throw e;
		}
	}
	
	/**
	 * Verifies that all files in the given directory match the expected content hashes.
	 *
	 * @param path the directory containing the files to verify
	 * 
	 * @throws InterruptedException if verification is interrupted
	 * @throws IntegrityException if any file is missing or its content does not match its hash
	 * @throws ExecutionException if a verification task fails
	 */
	public void verifyDirectory(Path path) throws InterruptedException, IntegrityException, ExecutionException {
		verifyDirectory(path, true);
	}
	
	/**
	 * Verifies that all files in the given directory match the expected content hashes.
	 *
	 * @param path the directory containing the files to verify
	 * @param verifyDatabase whether to verify the vault's blobs first
	 * 
	 * @throws InterruptedException if verification is interrupted
	 * @throws IntegrityException if any file is missing or its content does not match its hash
	 * @throws ExecutionException if a verification task fails
	 */
	public void verifyDirectory(Path path, boolean verifyDatabase) throws InterruptedException, IntegrityException, ExecutionException {
		final SetMultimap<Hash, IntegrityProblem> problems = Multimaps.synchronizedSetMultimap(HashMultimap.create());
		
		LOGGER.info(marker, "Verifying " + path);
		
		if(verifyDatabase) {
			verifyBlobs();
		}
		else {
			LOGGER.warn(marker, "Skipping database verification.");
		}
		final BlobFactory factory = chrysalis.getBlobFactory();
		computeOverBlobs((hash, vaultDir, chrysalis, handler) -> {
			Set<Path> resources = chrysalis.blobs().get(hash);
			
			for(Path localizedResource : resources) {
				Path resource = path.resolve(localizedResource);
				if(!Files.exists(resource)) {
					throw new MissingResourceException("Missing Resource - " + hash + " (" + resource + ")", () -> resource);
				}
				try {
					BlobIO.verify(factory, resource, hash);
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
			LOGGER.info(marker, "Verification successful. All " + chrysalis.activeBlobs().size() + " active expected resources are present and valid.");
		}
		else {
			String message = "Verification Failed";
			IntegrityException e = new IntegrityException(message, problems.values().toArray(new IntegrityProblem[]{}));
			throw e;
		}
	}
	
	public final void computeOverBlobs(HashTask hashTask) throws InterruptedException, ExecutionException {
		computeOverBlobs(hashTask, HashTask.DEFAULT_PROBLEM_HANDLER);
	}
	
	/**
	 * Iterates over all blobs in the artifact and applies the given {@link HashTask}.
	 *
	 * <p>Ignored paths are excluded before tasks are scheduled. Each task receives
	 * a view containing only active expectations. A hash shared with an active
	 * path is still processed, but its ignored paths are absent from that view.
	 * <p>This method processes blobs in parallel using a thread pool
	 * sized according to the number of available processors.</p>
	 *
	 * @param hashTask the task to execute for each blob
	 * @param problemHandler a problem handler that can handle problems that occur when computing
	 * 
	 * @throws InterruptedException if execution is interrupted
	 * @throws ExecutionException if a task throws an exception
	 */
	public void computeOverBlobs(HashTask hashTask, HashTask.ProblemHandler problemHandler) throws InterruptedException, ExecutionException {
	    final Chrysalis active = chrysalis.checkingSnapshot();
	    Multiset<Hash> hashes = active.blobs().keys();
	    int threads = Runtime.getRuntime().availableProcessors();
	    ExecutorService executor = Executors.newFixedThreadPool(threads);
	    List<Future<Void>> futures = new ArrayList<>();

	    boolean[] terminated = new boolean[] {false};
	    
	    try {
	        for (Hash hash : hashes.elementSet()) {
	            Future<Void> future = executor.submit(() -> {
	            	if(!terminated[0]) {
	                    hashTask.call(hash, blobDir, active, problemHandler);
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

	/**
	 * Exports all files from this artifact to the specified destination directory.
	 *
	 * @param destDir the directory to export files to
	 * @param verifyBlobs whether to verify vault blobs before exporting
	 * 
	 * @throws InterruptedException if execution is interrupted
	 * @throws IntegrityException if any file verification fails
	 * @throws ExecutionException if a task fails
	 * @throws IOException if writing to the destination fails
	 */
	public void export(Path destDir, boolean verifyBlobs) throws InterruptedException, IntegrityException, ExecutionException {
		if(verifyBlobs) {
			verifyBlobs();
		}
		computeOverBlobs((hash, path, chrysalis, handler) -> {
			Path blob = path.resolve(hash.hash());
			Set<Path> dests = chrysalis.blobs().get(hash);
			for(Path relativeDest : dests) {
				Path dest = destDir.resolve(relativeDest);
				Files.createDirectories(dest.getParent());
				Files.copy(blob, dest);
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
	
	/**
	 * Checks whether this artifact has a corresponding Chrysalis file in the vault.
	 *
	 * @return {@code true} if the Chrysalis file exists
	 */
	public boolean hasChrysalis() {
		return sourceFile != null && Files.exists(sourceFile);
	}
	
	/**
	 * Returns the path to the Chrysalis file for this artifact.
	 *
	 * @return the path to the chrysalis JSON file
	 */
	public Path getChrysalisFile() {
		return sourceFile == null ? owningVault.getChrysalisFile(artifact, name) : sourceFile;
	}
	
	public Path getBlobFile(Hash hash) {
		return getBlobFile(blobDir, hash);
	}
	
	public Path getBlobFile(Path blobDir, Hash hash) {
		return blobDir.resolve(hash.hash());
	}
	
	/**
	 * Returns the underlying artifact for this ChrysalisizedVault.
	 *
	 * @return the wrapped IVaultable artifact
	 */
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
