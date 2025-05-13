package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.VersionAlreadyWeavedException;
import com.wildermods.thrixlvault.steam.IDownload;
import com.wildermods.thrixlvault.steam.IVersioned;


/**
 * The {@code Weaver} class is responsible for processing (or "weaving") a {@link Vault}
 * by scanning a source directory, computing content-based hashes, and writing any new
 * or updated blobs into the vault's designated blob store. It also serializes a 
 * {@link Chrysalis} manifest inside the vault's designated version directory for later
 * deserialization and validation.
 * <p>
 * If a version has already been weaved (i.e., its manifest already exists) and {@code force}
 * is not enabled, a {@link VersionAlreadyWeavedException} is thrown to prevent accidental overwrites.
 * <p>
 * This class is designed to ensure blob integrity and can validate that all blobs referenced
 * in a manifest exist and are unmodified.
 */
public class Weaver implements IVersioned {
	
	/** Gson instance for serializing Chrysalis manifests. */
	public static final Gson GSON;
	static {
		GsonBuilder builder = new GsonBuilder();
		builder.registerTypeAdapter(Chrysalis.class, new ChrysalisSerializer());
		builder.setPrettyPrinting().setFormattingStyle(FormattingStyle.PRETTY.withIndent("\t"));
		GSON = builder.create();
	}
	
	/** The default output directory (under the user's home directory). */
	public static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home")).resolve("thrixlvault");
	
	/** The directory where blob files are stored. */
	public static final Path BLOB_DIR = OUTPUT_DIR.resolve("blobs");
	
	private static final Logger LOGGER = LogManager.getLogger();
	
	private final ChrysalisizedVault vault;
	private final IVersioned version;
	private final Marker marker;
	
    /**
     * Constructs a new {@code Weaver} for the given vault and version string.
     *
     * @param vault the vault to weave into
     * @param version the version string to associate with the weaved result
     * @param sourcesDir the directory to scan for files
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity violation occurs
     */
	public Weaver(Vault vault, String version, Path sourcesDir) throws IOException, IntegrityException {
		this(vault, version, sourcesDir, false);
	}
	

    /**
     * Constructs a new {@code Weaver} with the option to force overwrite an existing weaved version.
     *
     * @param vault the vault weave into
     * @param version the version string to associate with the weaved result
     * @param sourcesDir the directory to scan for files
     * @param force whether to overwrite existing data
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity violation occurs
     */
	public Weaver(Vault vault, String version, Path sourcesDir, boolean force) throws IOException, IntegrityException {
		this.version = () -> version;
		this.vault = weave(sourcesDir, vault, force);
		this.marker = MarkerManager.getMarker(version);
	}
	
    /**
     * Constructs a new {@code Weaver} using a {@link IVersioned} version provider.
     *
     * @param vault the vault to weave into
     * @param versioned the version
     * @param sourcesDir the directory to scan for files
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity violation occurs
     */
	public Weaver(Vault vault, IVersioned versioned, Path sourcesDir) throws IOException, IntegrityException {
		this(vault, versioned.version(), sourcesDir);
	}
	
	
	/**
	 * Constructs a new {@code Weaver} using an {@link IDownload} to obtain the version and source directory.
	 * Utilizes the version and destination directory provided by the {@code IDownload} instance
	 * to weave the download into the vault.
	 *
	 * @param vault the vault to weave into
	 * @param download the {@link IDownload} instance providing the version and location of the source files
	 * @throws IOException if an I/O error occurs
	 * @throws IntegrityException if an integrity violation occurs
	*/
	public Weaver(Vault vault, IDownload download) throws IOException, IntegrityException {
	    this(vault, download.version(), download.dest());
	}
	
    /**
     * Gets the version associated with this weaving operation.
     *
     * @return the version string
     */
	@Override
	public String version() {
		return version.version();
	}
	
    /**
     * Gets the {@link ChrysalisizedVault} produced by this weaving operation.
     *
     * @return the processed vault
     */
	public ChrysalisizedVault getChrysalisizedVault() {
		return vault;
	}
	
    /**
     * Verifies the integrity of all blobs in the weaved vault.
     * This method ensures that all blobs referenced in the manifest exist and are unmodified.
     *
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if the version's integrity has been violated
     * @throws InterruptedException 
     * @throws ExecutionException 
     */
	public void verify() throws IntegrityException, IOException, InterruptedException, ExecutionException {
		vault.verifyBlobs();
	}
	
    /**
     * Internal method that performs the actual weaving process:
     * <ul>
     *   <li>Scans {@code sourceDir} and computes content hashes.</li>
     *   <li>Writes new or changed blobs to {@link Vault#blobDir}.</li>
     *   <li>Writes a versioned Chrysalis manifest JSON file.</li>
     * </ul>
     *
     * @param sourceDir the source directory to scan
     * @param vault the vault receiving the blobs and manifest
     * @param force whether to overwrite existing files
     * @return a new {@link ChrysalisizedVault} instance
     * @throws IOException if an I/O error occurs
     * @throws IntegrityException if an integrity issue is found
     */
	private ChrysalisizedVault weave(Path sourceDir, Vault vault, boolean force) throws IOException, IntegrityException {
		if(!force && Files.exists(vault.getChrysalisFile(this))) {
			throw new VersionAlreadyWeavedException(version.version() + " in " + vault.getChrysalisFile(this));
		}
		
		AtomicLong preExistingBlobs = new AtomicLong();
		AtomicLong overwrittenBlobs = new AtomicLong();
		final Set<Path> writtenBlobs = ConcurrentHashMap.newKeySet();
		final OpenOption[] openOptions = !force ? new StandardOpenOption[] {StandardOpenOption.CREATE_NEW} : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
		Chrysalis chrysalis = Chrysalis.fromDir(sourceDir, (p, blob) -> {
			Path blobPath = vault.blobDir.resolve(blob.hash());
			p.set(sourceDir.relativize(p.get())); //set the path output to be relativized
			final boolean exists = Files.exists(blobPath);
			
			if(!force && exists) {
				preExistingBlobs.addAndGet(1);
			}
			else {
				try {
					if(writtenBlobs.add(blobPath)) {
						if(exists) {
							overwrittenBlobs.addAndGet(1);
						}
						
						Files.write(blobPath, blob.data(), openOptions);
					}
					else {
						LOGGER.warn(marker, "Skipping concurrent write of " + blobPath);
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		});

		LOGGER.info(marker, "");
		LOGGER.info(marker, "===================WEAVER RESULTS===================");
		LOGGER.info(marker, "Files found: " + chrysalis.blobs().keys().size());
		LOGGER.info(marker, "Unique Blobs: " + chrysalis.blobs().keySet().size());
		LOGGER.info(marker, "Duplicate Blobs " + (chrysalis.blobs().keys().size() - chrysalis.blobs().keySet().size()));
		//LOGGER.info(marker, "PreExisting/Duplicate Blobs: " + preExistingBlobs);
		LOGGER.info(marker, "Blobs overwritten: "+ overwrittenBlobs);
		LOGGER.info(marker, "Blobs written: " + writtenBlobs.size());
		LOGGER.info(marker, "====================================================");
		LOGGER.info(marker, "");
		
		//Serialize and write the chrysalis to the version-specific JSON file
		try {
			Path chrysalisFile = vault.getChrysalisFile(this);
			Path parent = chrysalisFile.getParent();
			
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			Files.writeString(chrysalisFile, GSON.toJson(chrysalis), openOptions);
			LOGGER.info(marker, "Wrote weaved version to: " + chrysalisFile);
		} catch (IOException e) {
			throw new IOException("Failed to write weaved data to JSON", e);
		}
		
		return vault.chrysalisize(this);
	}
	
}
