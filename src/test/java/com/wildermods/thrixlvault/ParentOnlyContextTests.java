package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.thrixlvault.steam.IVaultable;

class ParentOnlyContextTests {
	@TempDir Path temp;
	private static final IVaultable ARTIFACT = ChrysalisNavigationTests.ARTIFACT;
	private JsonObject json(Object value) { return Weaver.GSON.toJsonTree(value).getAsJsonObject(); }

	@Test void aRootIsTheSameOperationsAppliedToEmptyStateWithNoTypeField() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var factory = new BlobFactory();
		var hash = factory.blob(new byte[] {1});
		var operations = ChrysalisModification.builder("SHA-1").add(Path.of("one"), hash).add(Path.of("copy"), hash).ignore(Path.of("cache")).build();
		var root = vault.saveModification(ARTIFACT, "blobs", null, operations, Set.of("strict"), Set.of("absent"));
		var empty = vault.saveSnapshot(ARTIFACT, "empty", Chrysalis.EMPTY.clone(), Set.of());
		var child = vault.saveModification(ARTIFACT, "child", "empty", operations, Set.of("strict"), Set.of("absent"));
		assertEquals(root.getChrysalis(), child.getChrysalis());
		assertEquals(root.getTags(), child.getTags());
		assertTrue(root.getParent().isEmpty());
		assertEquals("empty", child.getParent().orElseThrow().getContextName());
		JsonObject rootJson = json(root.getContext().orElseThrow());
		JsonObject childJson = json(child.getContext().orElseThrow());
		assertFalse(rootJson.has("type")); assertFalse(childJson.has("type"));
		assertEquals(4, rootJson.get("schema").getAsInt());
		assertEquals(rootJson.keySet(), childJson.keySet());
		childJson.add("parent", com.google.gson.JsonNull.INSTANCE);
		assertEquals(rootJson, childJson);
	}

	@Test void missingAndNullParentsResolveTheSameRootAndNewSavesEmitNoType() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var operations = ChrysalisModification.builder("SHA-256").add(Path.of("file"), new BlobFactory("SHA-256").blob(new byte[] {1})).build();
		var root = vault.saveModification(ARTIFACT, "root", null, operations);
		JsonObject declaration = json(root.getContext().orElseThrow());
		declaration.remove("parent");
		Path file = vault.getChrysalisFile(ARTIFACT, "missing-parent");
		Files.writeString(file, declaration.toString());
		var loaded = vault.load(ARTIFACT, file);
		assertEquals(root.getChrysalis(), loaded.getChrysalis());
		assertTrue(loaded.getParent().isEmpty());
		assertEquals("SHA-256", loaded.getChrysalis().getBlobFactory().algorithm());
		var copied = loaded.saveAs("copied");
		JsonObject saved = com.google.gson.JsonParser.parseString(Files.readString(copied.getChrysalisFile())).getAsJsonObject();
		assertTrue(saved.get("parent").isJsonNull()); assertFalse(saved.has("type"));
	}

	@Test void rootReplacementRemovalAndRemoveIgnoreFailThroughNormalApplicationRules() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var hash = new BlobFactory().blob(new byte[] {1});
		for (var operations : new ChrysalisModification[] {
			ChrysalisModification.builder("SHA-1").replace(Path.of("file"), hash).build(),
			ChrysalisModification.builder("SHA-1").remove(Path.of("file")).build(),
			ChrysalisModification.builder("SHA-1").removeIgnore(Path.of("cache")).build()
		}) {
			assertThrows(IllegalArgumentException.class, () -> vault.saveModification(ARTIFACT, "invalid", null, operations));
			assertFalse(vault.hasChrysalis(ARTIFACT, "invalid"));
			Files.createDirectories(vault.getChrysalisFile(ARTIFACT).getParent());
			Path file = vault.getChrysalisFile(ARTIFACT, "invalid-file");
			Files.writeString(file, Weaver.GSON.toJson(VaultContext.modification(null, operations, Set.of(), Set.of())));
			assertThrows(IllegalArgumentException.class, () -> vault.load(ARTIFACT, file));
		}
	}

	@Test void rootAndChildTagsUseTheSameRulesIncludingRootNoOpRemovals() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = Files.createDirectory(temp.resolve("source")); Files.writeString(source.resolve("file"), "data");
		var root = new Weaver(vault, ARTIFACT, source).tag("strict", "unknown:vendor").removeTag("absent").weave();
		assertEquals(Set.of("absent"), root.getRemovedTags());
		assertEquals(Set.of("strict", "unknown:vendor"), root.getTags());
		var child = new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").removeTag("strict").tag("modded").weave();
		assertEquals(Set.of("modded", "unknown:vendor"), child.getTags());
		for (var declaration : new ChrysalisizedVault[] {root, child}) {
			var reloaded = vault.chrysalisize(ARTIFACT, declaration.getContextName());
			assertEquals(declaration.getRemovedTags(), reloaded.getRemovedTags());
			assertEquals(declaration.getChrysalis(), reloaded.getChrysalis());
			assertFalse(json(reloaded.getContext().orElseThrow()).has("type"));
		}
	}
}
