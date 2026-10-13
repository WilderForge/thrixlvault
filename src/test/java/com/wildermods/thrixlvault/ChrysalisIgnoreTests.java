package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import com.google.gson.JsonParseException;
import com.wildermods.masshash.Hash;
import com.wildermods.thrixlvault.exception.DatabaseIntegrityError;
import com.wildermods.thrixlvault.steam.IVaultable;

class ChrysalisIgnoreTests {
	@TempDir Path temp;

	@Test
	void ignoredCopiesAndEntireDirectoriesAreNeverReadOrExported() throws Exception {
		Fixture f = fixture();
		var patch = builder(f.base).ignore(Path.of("cache")).build();
		var ignored = f.base.withModifications("ignored", patch);
		Files.delete(f.source.resolve("cache/shared"));
		Files.delete(f.source.resolve("cache/unique"));
		Files.delete(f.base.getBlobFile(f.unique));
		ignored.verifyDirectory(f.source, true);
		AtomicInteger calls = new AtomicInteger();
		ignored.computeOverBlobs((hash, directory, active, handler) -> {
			calls.incrementAndGet();
			assertTrue(hash.hashEquals(f.shared));
			assertEquals(Set.of(Path.of("keep")), active.blobs().get(hash));
		});
		assertEquals(1, calls.get());
		Path dest = temp.resolve("export");
		ignored.export(dest, true);
		assertTrue(Files.exists(dest.resolve("keep")));
		assertFalse(Files.exists(dest.resolve("cache")));
		assertEquals(3, ignored.getChrysalis().blobs().size());
		assertEquals(1, ignored.getChrysalis().activeBlobs().size());
		assertTrue(f.base.getChrysalis().getIgnoredPaths().isEmpty());
		var all = ignored.withModifications("all-ignored", builder(f.base).ignore(Path.of("keep")).build());
		Files.delete(f.source.resolve("keep"));
		Files.delete(f.base.getBlobFile(f.shared));
		all.verifyDirectory(f.source, true);
		assertTrue(all.getChrysalis().activeBlobs().isEmpty());
		all.computeOverBlobs((hash, directory, active, handler) -> fail("No ignored blob should be scheduled"));
	}

	@Test
	void fileIgnoreDoesNotHideAnotherCopyOfTheSameHash() throws Exception {
		Fixture f = fixture();
		var ignored = f.base.withModifications("ignored",
			builder(f.base).ignore(Path.of("cache/shared")).build());
		Files.writeString(f.source.resolve("cache/shared"), "corrupt ignored file");
		ignored.verifyDirectory(f.source, true);
		assertTrue(ignored.getChrysalis().isIgnored(Path.of("cache/shared")));
		assertFalse(ignored.getChrysalis().isIgnored(Path.of("cache/unique")));
		Files.writeString(f.base.getBlobFile(f.shared), "corrupt active shared blob");
		assertThrows(DatabaseIntegrityError.class, ignored::verifyBlobs);
	}

	@Test
	void removeIgnoreRestoresInheritedExpectationsAndVerification() throws Exception {
		Fixture f = fixture();
		var ignored = f.base.withModifications("ignored", builder(f.base).ignore(Path.of("cache")).build());
		var restore = builder(f.base).removeIgnore(Path.of("cache")).build();
		var restored = ignored.withModifications("restored", restore);
		assertEquals(f.base.getChrysalis(), restored.getChrysalis());
		restored.verifyDirectory(f.source, true);
		Files.delete(f.source.resolve("cache/shared"));
		ignored.verifyDirectory(f.source, false);
		assertThrows(ExecutionException.class, () -> restored.verifyDirectory(f.source, false));
		assertTrue(ignored.getChrysalis().isIgnored(Path.of("cache/shared")));
		assertThrows(IllegalArgumentException.class, () -> restore.applyTo(f.base.getChrysalis()));
	}

	@Test
	void removingExactNestedRootsPreservesOtherExplicitIgnores() throws Exception {
		Fixture f = fixture();
		var state = builder(f.base).ignore(Path.of("cache")).ignore(Path.of("cache/nested"))
			.build().applyTo(f.base.getChrysalis());
		assertTrue(state.isIgnored(Path.of("cache/missing/deep/file")));
		assertFalse(state.isIgnored(Path.of("cache-old/file")));
		var removeChild = builder(f.base).removeIgnore(Path.of("cache/nested")).build().applyTo(state);
		assertTrue(removeChild.isIgnored(Path.of("cache/nested/file")));
		var removeParent = builder(f.base).removeIgnore(Path.of("cache")).build().applyTo(state);
		assertFalse(removeParent.isIgnored(Path.of("cache/shared")));
		assertTrue(removeParent.isIgnored(Path.of("cache/nested/file")));
		assertThrows(IllegalArgumentException.class, () -> builder(f.base)
			.removeIgnore(Path.of("cache/shared")).build().applyTo(state));
		assertEquals(state, builder(f.base).ignore(Path.of("cache")).build().applyTo(state));
	}

	@Test
	void ignoreChangesCanCoexistWithFileChangesAndAreIncludedInDiffs() throws Exception {
		Fixture f = fixture();
		var patch = builder(f.base).replace(Path.of("cache/shared"), f.shared, f.unique)
			.ignore(Path.of("cache/shared")).build();
		var target = patch.applyTo(f.base.getChrysalis());
		assertTrue(target.blobs().containsEntry(f.unique, Path.of("cache/shared")));
		assertFalse(target.activeBlobs().containsEntry(f.unique, Path.of("cache/shared")));
		var restored = builder(f.base).removeIgnore(Path.of("cache/shared")).build().applyTo(target);
		assertTrue(restored.activeBlobs().containsEntry(f.unique, Path.of("cache/shared")));
		assertEquals(target, ChrysalisModification.between(f.base.getChrysalis(), target)
			.applyTo(f.base.getChrysalis()));
		assertEquals(f.base.getChrysalis(), ChrysalisModification.between(target, f.base.getChrysalis())
			.applyTo(target));
		assertNotEquals(f.base.getChrysalis(), builder(f.base).ignore(Path.of("cache"))
			.build().applyTo(f.base.getChrysalis()));
	}

	@Test
	void ignoresSurviveCloneAndBothJsonFormats() throws Exception {
		Fixture f = fixture();
		var patch = builder(f.base).ignore(Path.of("cache")).ignore(Path.of("missing/file")).build();
		var loadedPatch = Weaver.GSON.fromJson(Weaver.GSON.toJson(patch), ChrysalisModification.class);
		var ignored = loadedPatch.applyTo(f.base.getChrysalis());
		assertEquals(patch.ignoredPaths(), ignored.getIgnoredPaths());
		assertEquals(ignored, ignored.clone());
		String json = Weaver.GSON.toJson(ignored);
		assertTrue(json.contains("\"schema\": 3"));
		Chrysalis loaded = Weaver.GSON.fromJson(json, Chrysalis.class);
		assertEquals(ignored, loaded);
		assertEquals(1, loaded.activeBlobs().size());
		Path file = temp.resolve("resolved.json");
		Files.writeString(file, json);
		assertEquals(ignored, Chrysalis.fromFile(file));
		var unignore = builder(f.base).removeIgnore(Path.of("cache")).removeIgnore(Path.of("missing/file")).build();
		var parsedUnignore = Weaver.GSON.fromJson(Weaver.GSON.toJson(unignore), ChrysalisModification.class);
		assertEquals(f.base.getChrysalis(), parsedUnignore.applyTo(loaded));
		assertTrue(Weaver.GSON.toJson(f.base.getChrysalis()).contains("\"schema\": 3"));
		assertTrue(Weaver.GSON.fromJson(Weaver.GSON.toJson(f.base.getChrysalis()), Chrysalis.class)
			.getIgnoredPaths().isEmpty());
	}

	@Test
	void invalidAndConflictingIgnoreOperationsAreRejected() throws Exception {
		Fixture f = fixture();
		assertThrows(IllegalArgumentException.class, () -> builder(f.base).ignore(Path.of("cache"))
			.removeIgnore(Path.of("cache")));
		assertThrows(IllegalArgumentException.class, () -> builder(f.base).removeIgnore(Path.of("cache"))
			.ignore(Path.of("cache")));
		assertThrows(IllegalArgumentException.class, () -> new ChrysalisModification("SHA-1", Map.of(),
			Set.of(Path.of("cache")), Set.of(Path.of("cache"))));
		assertThrows(IllegalArgumentException.class, () -> builder(f.base).ignore(Path.of("../escape")));
		assertThrows(IllegalArgumentException.class, () -> builder(f.base).removeIgnore(Path.of("../escape")));
		String prefix = "{\"schema\":2,\"algorithm\":\"SHA-1\",\"changes\":{},";
		for (String suffix : Set.of("\"ignore\":[\"../escape\"]}",
			"\"ignore\":[\"cache\",\"a/../cache\"]}",
			"\"ignore\":[\"cache\"],\"removeIgnore\":[\"cache\"]}")) {
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(prefix + suffix, ChrysalisModification.class));
		}
		var patch = builder(f.base).ignore(Path.of("cache")).build();
		assertThrows(UnsupportedOperationException.class, () -> patch.ignoredPaths().clear());
		assertThrows(UnsupportedOperationException.class, () -> patch.applyTo(f.base.getChrysalis()).getIgnoredPaths().clear());
	}

	@Test
	void fiftyThousandIgnoredDescendantsAreRetainedButNeverScheduled() throws Exception {
		Fixture f = fixture();
		Chrysalis base = f.base.getChrysalis().clone();
		SetMultimap<Hash, Path> files = HashMultimap.create(base.blobs());
		for (int i = 0; i < 50_000; i++) {
			files.put(f.unique, Path.of("cache", "nested", "file-" + i));
		}
		base.setBlobs(files);
		var ignored = builder(f.base).ignore(Path.of("cache")).build().applyTo(base);
		assertEquals(50_003, ignored.blobs().size());
		assertEquals(1, ignored.activeBlobs().size());
		assertEquals(base, builder(f.base).removeIgnore(Path.of("cache")).build().applyTo(ignored));
	}

	private ChrysalisModification.Builder builder(ChrysalisizedVault base) {
		return ChrysalisModification.builder(base.getChrysalis().getBlobFactory().algorithm());
	}

	private Fixture fixture() throws Exception {
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.createDirectory(source.resolve("cache"));
		Files.writeString(source.resolve("keep"), "shared");
		Files.writeString(source.resolve("cache/shared"), "shared");
		Files.writeString(source.resolve("cache/unique"), "unique");
		IVaultable artifact = new IVaultable() {
			public String name() { return "test"; }
			public Path artifactPath() { return Path.of("test"); }
		};
		var base = new Weaver(new Vault(temp.resolve("vault")), artifact, source).weave();
		Hash shared = base.getChrysalis().getBlobFactory().blob(source.resolve("keep"));
		Hash unique = base.getChrysalis().getBlobFactory().blob(source.resolve("cache/unique"));
		return new Fixture(base, source, shared, unique);
	}

	private record Fixture(ChrysalisizedVault base, Path source, Hash shared, Hash unique) {}
}
