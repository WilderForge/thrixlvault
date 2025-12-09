package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.wildermods.masshash.Blob;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.DatabaseIntegrityError;
import com.wildermods.thrixlvault.exception.UnknownVersionException;
import com.wildermods.thrixlvault.steam.IVaultable;
import com.wildermods.thrixlvault.utils.OS;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WeaverTests {

	static final IVaultable VERSION = new IVaultable() {

		@Override
		public String name() {
			return "thrixlVaultTest";
		}

		@Override
		public Path artifactPath() {
			return Path.of("thrixlVaultTest");
		}
		
	};
	static Path sourceDir = Paths.get("./src", "test", "resources");
	

	
	static Path outputDir = Paths.get("./build").resolve("test");
	static Path vaultDir = outputDir.resolve("thrixlvault");
	
	static Vault vault;;
	
	static Weaver weaver;
	static Chrysalis chrysalis;
	static Hash randomHash;
	static boolean previousFailed = false;
	
	@BeforeAll
	static void setup() throws IOException {
		
		if(vaultDir.equals(Weaver.OUTPUT_DIR) || outputDir.equals(Weaver.OUTPUT_DIR)) {
			throw new Error("Tests were ran on production?!");
		}
		
		vault = new Vault(vaultDir);

		extractResources(sourceDir, outputDir);
	}
	
	@BeforeEach
	public void assumeNotFailed() {
		assumeFalse(previousFailed);
	}
	
	@RegisterExtension
	TestWatcher watcher = new TestWatcher() {
		@Override
		public void testFailed(ExtensionContext context, Throwable cause) {
			previousFailed = true;
		}
	};
	
	@Test
	@Order(1)
	void chrysalisSerializeTest() throws IOException {
		System.out.println("Test is running...");
		Chrysalis chrysalis = Chrysalis.fromDir(sourceDir);
		Path output = vaultDir.resolve("result.json");
		String json = Weaver.GSON.toJson(chrysalis);
	}
	
	@Test
	@Order(2)
	void weaverTest() throws IOException, IntegrityException {
		System.out.println("Weave test:");
		weaver = new Weaver(vault, VERSION, sourceDir);
	}
	
	@Test
	@Order(3)
	void databaseVerifyTest() throws IntegrityException, IOException, InterruptedException, ExecutionException {
		System.out.println("Verification test:");
		weaver.verify();
	}
	
	@Test
	@Order(4)
	void databaseCorruptionTest() {
	   	System.out.println("Corruption test:");
	   	Blob corruptBlob = new Blob("corrupt".getBytes());
	   	chrysalis = weaver.getChrysalisizedVault().chrysalis;
	   	
		chrysalis.blobs().keySet().forEach((hash) -> {
			try {
				Path blob = weaver.getChrysalisizedVault().getBlobFile(hash);
				System.out.println("Corrupting " + blob);
				Files.write(blob, corruptBlob.data(), StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		DatabaseIntegrityError e = assertThrowsExactly(DatabaseIntegrityError.class, () -> weaver.verify());
		e.printStackTrace();
		chrysalis.blobs().keySet().forEach((hash) -> {
			assertTrue(
				e.getMessage().contains("Corrupted blob - Expected hash " + hash.hash() + " but got " + corruptBlob.hash()),
				"Expected corrupted blob message for hash" + hash.hash() + " with corruption value of " + corruptBlob.hash());
		});
	}
	
	@Test
	@Order(5)
	void databaseOverwriteTest() throws IOException, IntegrityException {
		System.out.println("Force overwrite test:");
		weaver = new Weaver(vault, VERSION, sourceDir, true);
	}
	
	@Test
	@Order(6)
	void verifyOverwriteTest() throws IntegrityException, IOException, InterruptedException, ExecutionException {
		System.out.println("Overwrite Verification");
		weaver.verify();
		chrysalis = weaver.getChrysalisizedVault().chrysalis;
	}
	

	@Test
	@Order(7)
	@SuppressWarnings("unused")
	void missingResourceTest() throws IOException {
		Set<Hash> blobs = chrysalis.blobs().keySet();

		randomHash = blobs.stream().skip(new Random().nextInt(blobs.size())).findFirst().orElseThrow();
		Path blobFile = weaver.getChrysalisizedVault().getBlobFile(randomHash);
		System.out.println("Blob file: " + blobFile);
		Blob blob = new Blob(blobFile);
		
		Files.delete(blobFile);
		DatabaseIntegrityError e = assertThrowsExactly(DatabaseIntegrityError.class, () -> weaver.verify());
		assertTrue(
			e.getMessage().contains("Missing blob - " + randomHash.hash()),
			"Expected missing blob message for hash " + randomHash.hash());
	}
	
	@Test
	@Order(8)
	void overwriteRecreationTest() throws IOException, IntegrityException {
		new Weaver(vault, VERSION, sourceDir, true);
	}
	
	@Test
	@Order(9)
	void verifyOverwriteRecreationTest() throws IntegrityException, IOException, InterruptedException, ExecutionException {
		System.out.println("Overwrite recreation test");
		weaver.verify();
		assertTrue(Files.exists(weaver.getChrysalisizedVault().getBlobFile(randomHash)));
	}
	
	@Test
	@Order(10)
	void serializationTest() {
		Weaver.GSON.toJsonTree(chrysalis);
	}
	
	@Test
	@Order(11)
	void deserializationTest() {
		Weaver.GSON.fromJson(Weaver.GSON.toJsonTree(chrysalis), Chrysalis.class);
	}
	
	@Test
	@Order(12)
	void deserializeManifestTest() throws JsonSyntaxException, JsonIOException, IOException, UnknownVersionException {
		weaver.getChrysalisizedVault();
		
		Path manifest = weaver.getChrysalisizedVault().getChrysalisFile();
		System.out.println(manifest);
		assertTrue(Files.exists(manifest));
		
		Chrysalis deserialized = Chrysalis.fromFile(manifest);
		String chrysalisJson = Weaver.GSON.toJson(chrysalis);

		assertTrue(chrysalisJson.equals(Weaver.GSON.toJson(deserialized)));
		assertTrue(chrysalisJson.equals(Files.readString(manifest)));
		for(Hash hash : chrysalis.blobs().keySet()) {
			assertTrue(chrysalisJson.contains(hash.hash()), "Hash string not found in serialized manifest!: " + hash);
		}
		
		weaver.getChrysalisizedVault().purge();
	}
	
	@Test
	@Order(13)
	void osAssertions() throws IOException, IntegrityException {
		
		System.out.println("Weaving linux");
		Weaver linux = new Weaver(new Vault(vaultDir.resolve(OS.LINUX.name())), VERSION, sourceDir);
		
		System.out.println("Weaving windows");
		Weaver windows = new Weaver(new Vault(vaultDir.resolve(OS.WINDOWS.name())), VERSION, sourceDir);
		
		System.out.println("Weaving mac");
		Weaver mac = new Weaver(new Vault(vaultDir.resolve(OS.MAC.name())), VERSION, sourceDir);
		
		String jCurrent = Weaver.GSON.toJson(chrysalis);
		
		
		Chrysalis cLinux = linux.getChrysalisizedVault().chrysalis;
		String jLinux = Weaver.GSON.toJson(cLinux);
		
		
		Chrysalis cWindows = windows.getChrysalisizedVault().chrysalis;
		String jWindows = Weaver.GSON.toJson(cWindows);
		
		
		Chrysalis cMac = mac.getChrysalisizedVault().chrysalis;
		String jMac = Weaver.GSON.toJson(cMac);
		
		assertTrue(jCurrent.equals(jLinux));
		assertTrue(jCurrent.equals(jWindows));
		assertTrue(jCurrent.equals(jMac));
		
		try {
			linux.getChrysalisizedVault().purge();
		}
		catch(Throwable t) {
			t.printStackTrace();
		}
		
		try {
			windows.getChrysalisizedVault().purge();
		}
		catch(Throwable t) {
			t.printStackTrace();
		}
		
		try {
			mac.getChrysalisizedVault().purge();
		}
		catch(Throwable t) {
			t.printStackTrace();
		}
		
	}
	
	@AfterAll
	void cleanup() {
		
		if(!vaultDir.equals(Weaver.OUTPUT_DIR) && !outputDir.equals(Weaver.OUTPUT_DIR)) {
			try {
				deleteDirectory(outputDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		else {
			throw new Error("Tests were ran on production?!");
		}
		
	}
	
	private static void extractResources(Path sourceDir, Path outputDir) throws IOException {
		if (Files.exists(sourceDir) && Files.isDirectory(sourceDir)) {
			Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					// Calculate the relative path for the file to maintain structure
					Path relativePath = sourceDir.relativize(file);
					Path destination = outputDir.resolve(relativePath);

					// Create parent directories if they don't exist
					if (Files.notExists(destination.getParent())) {
						Files.createDirectories(destination.getParent());
					}

					// Copy the file
					Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
					// Handle errors gracefully and continue walking
					exc.printStackTrace();
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					// Ensure all directories are processed
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			throw new FileNotFoundException(sourceDir + "");
		}
	}
	
	private static void deleteDirectory(Path directory) throws IOException {
		if(Files.exists(directory)) {
			Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}
	
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}
	
}
