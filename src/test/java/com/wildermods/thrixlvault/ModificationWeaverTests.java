package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.VersionAlreadyWeavedException;
import com.wildermods.thrixlvault.steam.IVaultable;

class ModificationWeaverTests {
	@TempDir Path temp;
	private static final IVaultable ARTIFACT = ChrysalisNavigationTests.ARTIFACT;
	private ChrysalisizedVault weave(Vault vault, String name, String parent, Path directory) throws Exception {
		return Weaver.weaveModification(vault, ARTIFACT, name, parent, directory, Set.of(), Set.of(), Set.of(), Set.of());
	}
	private Path directory(String name) throws Exception { return Files.createDirectory(temp.resolve(name)); }
	private void file(Path directory, String name, String content) throws Exception {
		Path file = directory.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, content);
	}

	@Test void weavesManyPathsWithSharedHashesAndStoresNewBytesWithoutIntermediateSnapshot() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path original = directory("original");
		file(original, "unchanged", "old"); file(original, "replace", "old"); file(original, "remove", "old");
		new Weaver(vault, ARTIFACT, original).weave();
		Path desired = directory("desired");
		file(desired, "unchanged", "old"); file(desired, "replace", "new");
		for (int i = 0; i < 2000; i++) file(desired, "copies/" + i, "new");
		var modded = weave(vault, "modded", "blobs", desired);
		assertEquals(2002, modded.getChrysalis().blobs().size());
		assertEquals(2, modded.getChrysalis().blobs().keySet().size());
		assertFalse(modded.getChrysalis().blobs().values().contains(Path.of("remove")));
		modded.verifyBlobs();
		modded.verifyDirectory(desired);
		var json = Weaver.GSON.fromJson(Files.readString(modded.getChrysalisFile()), com.google.gson.JsonObject.class);
		assertEquals("modification", json.get("type").getAsString());
		assertFalse(json.getAsJsonObject("content").has("blobs"));
		assertEquals(2002, json.getAsJsonObject("content").getAsJsonObject("changes").size());
		try (var entries = Files.list(modded.getChrysalisFile().getParent())) { assertEquals(2, entries.count()); }
		try (var blobs = Files.list(vault.blobDir)) { assertEquals(2, blobs.count()); }
	}

	@Test void inheritedIgnoresAndTagOperationsAreExplicitAndIgnoredBytesAreStillStored() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = directory("source"); file(source, "cache/data", "data"); file(source, "keep", "keep");
		var base = new Weaver(vault, ARTIFACT, source).weave();
		var ignored = ChrysalisModification.builder("SHA-1").ignore(Path.of("cache")).build().applyTo(base.getChrysalis());
		vault.saveSnapshot(ARTIFACT, "base", ignored, Set.of("inherited"));
		file(source, "cache/data", "changed"); file(source, "hidden/data", "hidden");
		var child = Weaver.weaveModification(vault, ARTIFACT, "modded", "base", source,
			Set.of(Path.of("hidden")), Set.of(Path.of("cache")), Set.of("strict"), Set.of("inherited"));
		assertFalse(child.getChrysalis().isIgnored(Path.of("cache/data")));
		assertTrue(child.getChrysalis().isIgnored(Path.of("hidden/data")));
		assertEquals(Set.of("strict"), child.getTags());
		assertEquals(3, child.getChrysalis().blobs().size());
		assertEquals(2, child.getChrysalis().activeBlobs().size());
		for (var hash : child.getChrysalis().blobs().keySet()) assertTrue(Files.exists(child.getBlobFile(hash)));
		var restored = vault.saveModification(ARTIFACT, "restored", "modded", ChrysalisModification.builder("SHA-1").removeIgnore(Path.of("hidden")).build());
		restored.verifyDirectory(source);
	}

	@Test void emptyDesiredDirectoryRemovesEverythingAndParentlessDirectoriesStartEmpty() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = directory("source"); file(source, "old", "old");
		new Weaver(vault, ARTIFACT, source).weave();
		var empty = weave(vault, "empty", "blobs", directory("empty"));
		assertTrue(empty.getChrysalis().blobs().isEmpty());
		var standalone = weave(vault, "standalone", null, source);
		assertTrue(standalone.getParent().isEmpty());
		assertSame(standalone, standalone.getRoot());
		assertEquals(1, standalone.getChrysalis().blobs().size());
	}

	@Test void parentAlgorithmIsUsedWhenHashing() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveSnapshot(ARTIFACT, "base", Chrysalis.EMPTY.clone().factory(new BlobFactory("SHA-256")), Set.of());
		Path source = directory("source"); file(source, "file", "content");
		var result = weave(vault, "modded", "base", source);
		assertEquals("SHA-256", result.getChrysalis().getBlobFactory().algorithm());
		assertEquals(new BlobFactory("SHA-256").blob(source.resolve("file")).hash(), result.getChrysalis().blobs().keySet().iterator().next().hash());
		result.verifyDirectory(source);
	}

	@Test void validationFailuresDoNotPublishOrStoreNewBytesAndOverwriteIsExplicit() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveSnapshot(ARTIFACT, "blobs", Chrysalis.EMPTY.clone(), Set.of());
		Path source = directory("source"); file(source, "file", "content");
		assertThrows(IllegalArgumentException.class, () -> Weaver.weaveModification(vault, ARTIFACT, "invalid", "blobs", source,
			Set.of(), Set.of(Path.of("not-ignored")), Set.of(), Set.of()));
		assertFalse(vault.hasChrysalis(ARTIFACT, "invalid"));
		try (var files = Files.list(vault.blobDir)) { assertEquals(0, files.count()); }
		weave(vault, "modded", "blobs", source);
		String previous = Files.readString(vault.getChrysalisFile(ARTIFACT, "modded"));
		file(source, "file", "changed");
		assertThrows(VersionAlreadyWeavedException.class, () -> weave(vault, "modded", "blobs", source));
		assertEquals(previous, Files.readString(vault.getChrysalisFile(ARTIFACT, "modded")));
		var replaced = Weaver.weaveModification(vault, ARTIFACT, "modded", "blobs", source,
			Set.of(), Set.of(), Set.of(), Set.of(), true);
		replaced.verifyDirectory(source);
	}

	@Test void corruptReusedBlobsAreRejectedWithoutPublishingManifest() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = directory("source"); file(source, "file", "content");
		var base = new Weaver(vault, ARTIFACT, source).weave();
		Files.writeString(base.getBlobFile(base.getChrysalis().blobs().keySet().iterator().next()), "corrupted");
		assertThrows(IntegrityException.class, () -> weave(vault, "modded", "blobs", source));
		assertFalse(vault.hasChrysalis(ARTIFACT, "modded"));
	}
}
