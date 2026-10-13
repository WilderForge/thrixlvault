package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.io.TempDir;

import com.wildermods.thrixlvault.exception.DatabaseVaultCircularityError;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.steam.IVaultable;

class ChrysalisNavigationTests {
	@TempDir Path temp;
	static final IVaultable ARTIFACT = new IVaultable() {
		public String name() { return "navigation"; }
		public Path artifactPath() { return Path.of("game", "version"); }
	};
	private ChrysalisModification empty() { return ChrysalisModification.builder("SHA-1").build(); }

	@Test void parentAndRootFollowTheDeclaredChainAndRootNeedNotBeBlobs() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var root = vault.saveModification(ARTIFACT, "root", null, empty(), Set.of("inherited"), Set.of());
		vault.saveModification(ARTIFACT, "child", "root", empty());
		vault.saveModification(ARTIFACT, "grandchild", "child", empty(), Set.of("strict"), Set.of("inherited"));
		assertTrue(root.getParent().isEmpty());
		assertSame(root, root.getRoot());
		var leaf = vault.loadContext(ARTIFACT, "grandchild");
		assertEquals("child", leaf.getParent().orElseThrow().getContextName());
		assertEquals("root", leaf.getRoot().getContextName());
		assertSame(vault, leaf.getRoot().getVault());
		assertEquals(Set.of("strict"), leaf.getTags());
		assertEquals(Set.of("inherited"), leaf.getRemovedTags());
		assertFalse(vault.hasChrysalis(ARTIFACT));
	}

	@Test void childLookupChecksImmediateParentAndNeverCreatesOrReparents() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var root = vault.saveSnapshot(ARTIFACT, "blobs", Chrysalis.EMPTY.clone(), Set.of());
		vault.saveModification(ARTIFACT, "child", "blobs", empty());
		vault.saveModification(ARTIFACT, "grandchild", "child", empty());
		vault.saveModification(ARTIFACT, "unrelated", null, empty());
		assertEquals("child", root.getChild("child").getContextName());
		assertThrows(IllegalArgumentException.class, () -> root.getChild("grandchild"));
		assertThrows(IllegalArgumentException.class, () -> root.getChild("unrelated"));
		assertThrows(IllegalArgumentException.class, () -> root.getChild("blobs"));
		assertThrows(MissingVersionException.class, () -> root.getChild("absent"));
		assertFalse(vault.hasChrysalis(ARTIFACT, "absent"));
	}

	@Test void arbitraryFilesLoadUniformlyAndSavePreservesLocalDeclaration() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveSnapshot(ARTIFACT, "blobs", Chrysalis.EMPTY.clone(), Set.of("inherited"));
		Path snapshot = temp.resolve("external-snapshot.json");
		Files.writeString(snapshot, Weaver.GSON.toJson(Chrysalis.EMPTY));
		var full = vault.load(ARTIFACT, snapshot);
		assertTrue(full.getParent().isEmpty());
		assertSame(full, full.getRoot());
		assertEquals(snapshot.toAbsolutePath(), full.getChrysalisFile());
		assertFalse(vault.hasChrysalis(ARTIFACT, "external-snapshot"));
		// External declarations inherit from siblings, not the owning vault directory.
		Path sibling = temp.resolve("blobs.json");
		Files.copy(vault.getChrysalisFile(ARTIFACT), sibling);
		Path patch = temp.resolve("external-modification.json");
		Files.writeString(patch, Weaver.GSON.toJson(VaultContext.modification("blobs", empty(), Set.of("strict", "vendor:custom"), Set.of("inherited"))));
		var modified = vault.load(ARTIFACT, patch);
		assertSame(vault, modified.getVault());
		assertEquals("blobs", modified.getParent().orElseThrow().getContextName());
		assertEquals(sibling, modified.getParent().orElseThrow().getChrysalisFile());
		assertThrows(IllegalStateException.class, () -> modified.getChild("any"));
		assertFalse(vault.hasChrysalis(ARTIFACT, "external-modification"));
		var saved = modified.saveAs("modded");
		assertEquals(Set.of("strict", "vendor:custom"), saved.getLocalTags());
		assertEquals(Set.of("inherited"), saved.getRemovedTags());
		assertEquals("blobs", saved.getParent().orElseThrow().getContextName());
		assertFalse(Weaver.GSON.fromJson(Files.readString(saved.getChrysalisFile()), com.google.gson.JsonObject.class).has("type"));
		assertEquals(saved.getChrysalis(), vault.load(ARTIFACT, saved.getChrysalisFile()).getChrysalis());
		assertEquals("modded", vault.chrysalisize(ARTIFACT).getChild("modded").getContextName());
	}

	@Test @Timeout(5)
	void externalSelfParentDoesNotFallBackToSameNameInVault() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveModification(ARTIFACT, "modded", null, empty());
		Path external = temp.resolve("modded.json");
		Files.writeString(external, Weaver.GSON.toJson(VaultContext.modification("modded", empty(), Set.of(), Set.of())));
		var error = assertThrows(DatabaseVaultCircularityError.class, () -> vault.load(ARTIFACT, external));
		assertTrue(error.getMessage().contains("modded.json->modded.json"));
		assertTrue(vault.chrysalisize(ARTIFACT, "modded").getParent().isEmpty());
	}

	@Test void externalParentMustBeASiblingWithoutFallbackToOwningVault() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveSnapshot(ARTIFACT, "blobs", Chrysalis.EMPTY.clone(), Set.of("vault-parent"));
		Path external = temp.resolve("external.json");
		Files.writeString(external, Weaver.GSON.toJson(VaultContext.modification("blobs", empty(), Set.of("child"), Set.of())));
		assertThrows(MissingVersionException.class, () -> vault.load(ARTIFACT, external));
		Path sibling = temp.resolve("blobs.json");
		Files.writeString(sibling, Weaver.GSON.toJson(VaultContext.snapshot(Chrysalis.EMPTY.clone(), Set.of("sibling-parent"))));
		var loaded = vault.load(ARTIFACT, external);
		assertEquals(Set.of("sibling-parent", "child"), loaded.getTags());
		assertEquals(sibling, loaded.getParent().orElseThrow().getChrysalisFile());
		assertSame(vault, loaded.getRoot().getVault());
	}

	@Test @Timeout(5)
	void storedCyclesReportTheirOrderedNamesAndSaveDoesNotOverwrite() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveModification(ARTIFACT, "a", null, empty());
		vault.saveModification(ARTIFACT, "b", "a", empty());
		vault.saveModification(ARTIFACT, "c", "b", empty());
		String previous = Files.readString(vault.getChrysalisFile(ARTIFACT, "a"));
		var error = assertThrows(DatabaseVaultCircularityError.class, () -> vault.saveModification(
			ARTIFACT, "a", "c", empty(), Set.of(), Set.of(), true));
		assertTrue(error.getMessage().contains("a.json->c.json->b.json->a.json"));
		assertEquals(previous, Files.readString(vault.getChrysalisFile(ARTIFACT, "a")));
		Path a = vault.getChrysalisFile(ARTIFACT, "a");
		Files.writeString(a, Weaver.GSON.toJson(VaultContext.modification("c", empty(), Set.of(), Set.of())));
		assertThrows(DatabaseVaultCircularityError.class, () -> vault.chrysalisize(ARTIFACT, "c"));
		assertThrows(DatabaseVaultCircularityError.class, () -> vault.load(ARTIFACT, a));
	}

	@Test void equalNamesInDifferentVaultsAndArtifactsRemainIndependent() throws Exception {
		Vault first = new Vault(temp.resolve("first"));
		Vault second = new Vault(temp.resolve("second"));
		IVaultable otherArtifact = new IVaultable() {
			public String name() { return "other"; }
			public Path artifactPath() { return Path.of("game", "other-version"); }
		};
		for (var entry : java.util.Map.of(first, "first", second, "second").entrySet()) {
			Vault vault = entry.getKey();
			vault.saveSnapshot(ARTIFACT, "blobs", Chrysalis.EMPTY.clone(), Set.of(entry.getValue()));
			vault.saveModification(ARTIFACT, "modded", "blobs", empty());
			vault.saveSnapshot(otherArtifact, "blobs", Chrysalis.EMPTY.clone(), Set.of("other"));
			vault.saveModification(otherArtifact, "modded", "blobs", empty());
			var child = vault.chrysalisize(ARTIFACT).getChild("modded");
			assertEquals(Set.of(entry.getValue()), child.getTags());
			assertSame(vault, child.getParent().orElseThrow().getVault());
			assertEquals(ARTIFACT.artifactPath(), child.getRoot().artifactPath());
			assertEquals(Set.of("other"), vault.chrysalisize(otherArtifact).getChild("modded").getTags());
		}
	}

	@Test void jsonParentsCannotNameOtherDirectoriesOrVaults() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path file = temp.resolve("external.json");
		var json = Weaver.GSON.toJsonTree(VaultContext.modification(null, empty(), Set.of(), Set.of())).getAsJsonObject();
		for (String parent : Set.of("../blobs", "/other/vault/blobs", "other/blobs", "other\\blobs", "C:blobs")) {
			json.addProperty("parent", parent);
			Files.writeString(file, json.toString());
			assertThrows(JsonParseException.class, () -> vault.load(ARTIFACT, file));
		}
	}

	@Test void externalJsonWithoutExtensionCanLoadADistinctNamedSibling() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path sibling = temp.resolve("modded.json");
		Files.writeString(sibling, Weaver.GSON.toJson(VaultContext.modification(null, empty(), Set.of("parent"), Set.of())));
		Path external = temp.resolve("modded");
		Files.writeString(external, Weaver.GSON.toJson(VaultContext.modification("modded", empty(), Set.of("child"), Set.of())));
		var loaded = vault.load(ARTIFACT, external);
		assertEquals(Set.of("parent", "child"), loaded.getTags());
		assertEquals(sibling, loaded.getParent().orElseThrow().getChrysalisFile());
	}

	@Test void temporaryViewsRetainAncestryButAreNotNamedDeclarations() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		var root = vault.saveSnapshot(ARTIFACT, "blobs", Chrysalis.EMPTY.clone(), Set.of());
		var view = root.withModifications("temporary", empty());
		assertSame(root, view.getParent().orElseThrow());
		assertSame(root, view.getRoot());
		assertFalse(view.hasChrysalis());
		assertThrows(IllegalStateException.class, () -> view.getChild("any"));
		assertThrows(IllegalStateException.class, () -> view.saveAs("temporary"));
	}
}
