package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParseException;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.exception.DatabaseVaultCircularityError;
import com.wildermods.thrixlvault.steam.IVaultable;

class VaultContextTests {
	@TempDir Path temp;
	private static final IVaultable ARTIFACT = new IVaultable() {
		public String name() { return "test"; }
		public Path artifactPath() { return Path.of("game", "version"); }
	};

	@Test
	void contextsShareTheVaultAndResolveFilesIgnoresAndTags() throws Exception {
		var f = fixture();
		var modification = builder().replace(Path.of("file"), f.original, f.replacement)
			.ignore(Path.of("cache")).build();
		var modded = f.vault.saveModification(ARTIFACT, "modded", "blobs", modification,
			Set.of("allow-extra-files", "vendor:unknown"), Set.of());
		var strict = f.vault.saveModification(ARTIFACT, "strict", "modded",
			builder().removeIgnore(Path.of("cache")).build(),
			Set.of("strict"), Set.of("allow-extra-files", "absent-tag"));
		assertEquals(Set.of("strict", "vendor:unknown"), strict.getTags());
		assertEquals(Set.of("strict"), strict.getContext().orElseThrow().tags());
		assertEquals("modded", strict.getContext().orElseThrow().parent());
		assertTrue(strict.getChrysalis().blobs().containsEntry(f.replacement, Path.of("file")));
		assertFalse(strict.getChrysalis().isIgnored(Path.of("cache/any")));
		assertTrue(modded.getChrysalis().isIgnored(Path.of("cache/any")));
		assertEquals(f.vault.blobDir, strict.blobDir);
		assertSame(f.vault, strict.getVault());
		assertEquals(f.vault.getChrysalisFile(ARTIFACT, "strict"), strict.getChrysalisFile());
		assertTrue(strict.hasChrysalis());
		var loaded = f.vault.chrysalisize(ARTIFACT, "strict");
		assertEquals(strict.getChrysalis(), loaded.getChrysalis());
		assertEquals(strict.getTags(), loaded.getTags());
		assertEquals(f.original.hash(), f.vault.chrysalisize(ARTIFACT).getChrysalis().blobs().keySet()
			.stream().filter(h -> h.hashEquals(f.original)).findFirst().orElseThrow().hash());
		Files.write(f.vault.blobDir.resolve(f.replacement.hash()), new byte[] {9});
		Path exported = temp.resolve("export");
		loaded.export(exported, true);
		assertArrayEquals(new byte[] {9}, Files.readAllBytes(exported.resolve("file")));
		var temporary = loaded.withModifications("temporary", builder().ignore(Path.of("file")).build());
		assertEquals(loaded.getTags(), temporary.getTags());
		assertTrue(temporary.getContext().isEmpty());
		assertSame(f.vault, temporary.getVault());
	}

	@Test
	void missingOrNullParentsStartEmptyWithoutLoadingBlobs() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var factory = new BlobFactory("SHA-256");
		Hash hash = factory.blob(new byte[] {1});
		var patch = ChrysalisModification.builder("SHA-256").add(Path.of("new"), hash).build();
		var standalone = vault.saveModification(ARTIFACT, "standalone", null, patch, Set.of("custom"), Set.of("absent"));
		assertFalse(vault.hasChrysalis(ARTIFACT));
		assertEquals("SHA-256", standalone.getChrysalis().getBlobFactory().algorithm());
		assertEquals(1, standalone.getChrysalis().blobs().size());
		assertEquals(Set.of("custom"), standalone.getTags());
		var json = Weaver.GSON.toJsonTree(vault.readDeclaration(ARTIFACT, "standalone")).getAsJsonObject();
		assertTrue(json.get("parent").isJsonNull());
		json.remove("parent");
		Files.writeString(vault.getChrysalisFile(ARTIFACT, "missing-parent"), json.toString());
		assertTrue(vault.chrysalisize(ARTIFACT, "missing-parent").getParent().isEmpty());
		json.add("parent", com.google.gson.JsonNull.INSTANCE);
		Files.writeString(vault.getChrysalisFile(ARTIFACT, "null-parent"), json.toString());
		assertEquals(standalone.getChrysalis(), vault.chrysalisize(ARTIFACT, "null-parent").getChrysalis());
		assertThrows(MissingVersionException.class, () -> vault.chrysalisize(ARTIFACT));
	}

	@Test
	void fullSnapshotTagsAndIgnoreStateSurvivePersistence() throws Exception {
		var f = fixture();
		var ignored = builder().ignore(Path.of("cache")).build().applyTo(f.base.getChrysalis());
		var saved = f.vault.saveSnapshot(ARTIFACT, "snapshot", ignored, Set.of("opaque:tag"));
		assertEquals(ignored, Chrysalis.fromFile(saved.getChrysalisFile()));
		assertEquals(Set.of("opaque:tag"), f.vault.chrysalisize(ARTIFACT, "snapshot").getTags());
		var context = saved.getContext().orElseThrow();
		assertNull(context.parent());
		assertNull(context.parent());
		assertThrows(UnsupportedOperationException.class, () -> saved.getTags().clear());
	}

	@Test
	void invalidParentsCyclesAndPreconditionsDoNotWriteOrOverwrite() throws Exception {
		var f = fixture();
		assertThrows(MissingVersionException.class, () -> f.vault.saveModification(ARTIFACT, "missing", "absent", builder().build()));
		assertFalse(f.vault.hasChrysalis(ARTIFACT, "missing"));
		assertThrows(DatabaseVaultCircularityError.class, () -> f.vault.saveModification(ARTIFACT, "self", "self", builder().build()));
		assertFalse(f.vault.hasChrysalis(ARTIFACT, "self"));
		f.vault.saveModification(ARTIFACT, "a", "blobs", builder().build());
		f.vault.saveModification(ARTIFACT, "b", "a", builder().build());
		String previous = Files.readString(f.vault.getChrysalisFile(ARTIFACT, "a"));
		assertThrows(DatabaseVaultCircularityError.class, () -> f.vault.saveContext(ARTIFACT, "a",
			VaultContext.modification("b", builder().build(), Set.of(), Set.of()), true));
		assertEquals(previous, Files.readString(f.vault.getChrysalisFile(ARTIFACT, "a")));
		assertThrows(IllegalArgumentException.class, () -> f.vault.saveContext(ARTIFACT, "a",
			VaultContext.modification("blobs", builder().replace(Path.of("file"), f.replacement, f.original).build(), Set.of(), Set.of()), true));
		assertEquals(previous, Files.readString(f.vault.getChrysalisFile(ARTIFACT, "a")));
		assertThrows(FileAlreadyExistsException.class, () -> f.vault.saveModification(ARTIFACT, "a", "blobs", builder().build()));
		Files.writeString(f.vault.getChrysalisFile(ARTIFACT, "x"), Weaver.GSON.toJson(
			VaultContext.modification("y", builder().build(), Set.of(), Set.of())));
		Files.writeString(f.vault.getChrysalisFile(ARTIFACT, "y"), Weaver.GSON.toJson(
			VaultContext.modification("x", builder().build(), Set.of(), Set.of())));
		assertThrows(DatabaseVaultCircularityError.class, () -> f.vault.chrysalisize(ARTIFACT, "x"));
	}

	@Test
	void defaultMustBeFullAndNamesCannotEscapeTheArtifactDirectory() throws Exception {
		var f = fixture();
		assertThrows(IllegalArgumentException.class, () -> f.vault.saveContext(ARTIFACT, "blobs",
			VaultContext.modification("absent", builder().build(), Set.of(), Set.of()), true));
		for (String name : Set.of("../escape", "a/b", "a\\b", "C:escape", ".", "..", " ")) {
			assertThrows(IllegalArgumentException.class, () -> f.vault.getChrysalisFile(ARTIFACT, name));
			assertThrows(IllegalArgumentException.class, () -> VaultContext.modification(name, builder().build(), Set.of(), Set.of()));
		}
	}

	@Test
	void legacyFullSchemasLoadAndUnknownTagsArePreserved() throws Exception {
		var f = fixture();
		assertTrue(f.vault.chrysalisize(ARTIFACT).getTags().isEmpty());
		var legacy = new com.google.gson.JsonObject();
		legacy.addProperty("schema", 1);
		legacy.add("blobs", Weaver.GSON.toJsonTree(f.base.getChrysalis()).getAsJsonObject().get("blobs"));
		Files.writeString(f.vault.getChrysalisFile(ARTIFACT, "legacy-one"), legacy.toString());
		assertEquals(f.base.getChrysalis(), f.vault.chrysalisize(ARTIFACT, "legacy-one").getChrysalis());
		Files.writeString(f.vault.getChrysalisFile(ARTIFACT, "legacy-zero"), legacy.get("blobs").toString());
		assertEquals(f.base.getChrysalis(), f.vault.chrysalisize(ARTIFACT, "legacy-zero").getChrysalis());
		var declared = VaultContext.modification("blobs", builder().build(), Set.of("strict", "third-party:flag"), Set.of());
		var roundtrip = Weaver.GSON.fromJson(Weaver.GSON.toJson(declared), VaultContext.class);
		assertEquals(declared.tags(), roundtrip.tags());
	}

	@Test
	void malformedDeclarationsAndConflictingTagsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> VaultContext.modification(null, builder().build(), Set.of("strict"), Set.of("strict")));
		String payload = "\"schema\":2,\"algorithm\":\"SHA-1\",\"changes\":{}";
		for (String json : Set.of(
			"{\"type\":\"unknown\"," + payload + "}",
			"{\"type\":\"modification\",\"parent\":[\"a\",\"b\"]," + payload + "}",
			"{\"type\":\"modification\",\"tags\":[true]," + payload + "}",
			"{\"type\":\"modification\",\"tags\":[\"strict\"],\"removeTags\":[\"strict\"]," + payload + "}",
			"{\"type\":\"modification\",\"parent\":\"a\",\"parent\":\"b\"," + payload + "}",
			"{\"type\":\"modification\",\"tags\":[\" \"]," + payload + "}",
			"{\"type\":\"modification\",\"unexpected\":1," + payload + "}")) {
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(json, VaultContext.class));
		}
	}

	@Test
	void propertyOrderAndExplicitOverwriteAreSupported() throws Exception {
		var f = fixture();
		String json = "{\"blobs\":{},\"replacements\":{},\"removals\":[],\"tags\":[\"strict\"],\"algorithm\":\"SHA-1\",\"parent\":\"blobs\",\"schema\":3}";
		var context = Weaver.GSON.fromJson(json, VaultContext.class);
		var saved = f.vault.saveContext(ARTIFACT, "ordered", context);
		assertEquals(Set.of("strict"), saved.getTags());
		f.vault.saveContext(ARTIFACT, "ordered", VaultContext.modification("blobs", builder().build(), Set.of("other"), Set.of()), true);
		assertEquals(Set.of("other"), f.vault.chrysalisize(ARTIFACT, "ordered").getTags());
	}

	private ChrysalisModification.Builder builder() { return ChrysalisModification.builder("SHA-1"); }

	@Test
	void oneSchemaCoversTheWholeDeclarationAndLegacyFilesUpgrade() {
		VaultContext context = VaultContext.modification("blobs", builder().build(), Set.of("strict"), Set.of());
		var json = Weaver.GSON.toJsonTree(context).getAsJsonObject();
		assertEquals(3, json.get("schema").getAsInt());
		assertFalse(json.has("content"));
		assertTrue(json.has("algorithm"));
		var parsed = Weaver.GSON.fromJson(json, VaultContext.class);
		assertEquals(context.parent(), parsed.parent());
		assertEquals(context.tags(), parsed.tags());
		String legacy = "{\"schema\":2,\"algorithm\":\"SHA-1\",\"blobs\":{}}";
		var root = Weaver.GSON.fromJson(legacy, VaultContext.class);
		assertNull(root.parent());
		var upgraded = Weaver.GSON.toJsonTree(root).getAsJsonObject();
		assertEquals(3, upgraded.get("schema").getAsInt());
		assertTrue(upgraded.has("blobs"));
		assertTrue(upgraded.has("replacements"));
		assertTrue(upgraded.has("removals"));
	}

	@Test
	void unsupportedOrMalformedContextSchemasAreRejected() {
		String content = "\"content\":{\"schema\":2,\"algorithm\":\"SHA-1\",\"changes\":{}}";
		for (String schema : Set.of("0", "2", "-1", "1.5", "\"1\"", "null", "true")) {
			String json = "{\"type\":\"modification\",\"schema\":" + schema + "," + content + "}";
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(json, VaultContext.class));
		}
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(
			"{\"type\":\"modification\"," + content + "}", VaultContext.class));
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(
			"{\"schema\":1," + content + "}", VaultContext.class));
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(
			"{\"schema\":1,\"type\":\"modification\",\"content\":null}", VaultContext.class));
	}
	private Fixture fixture() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.write(source.resolve("file"), new byte[] {1});
		Files.createDirectory(source.resolve("cache"));
		Files.write(source.resolve("cache/data"), new byte[] {1});
		var base = new Weaver(vault, ARTIFACT, source).weave();
		var factory = base.getChrysalis().getBlobFactory();
		return new Fixture(vault, base, factory.blob(new byte[] {1}), factory.blob(new byte[] {9}));
	}
	private record Fixture(Vault vault, ChrysalisizedVault base, Hash original, Hash replacement) {}
}
