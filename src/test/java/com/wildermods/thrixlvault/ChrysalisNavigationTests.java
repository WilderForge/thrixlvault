package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
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
		Path patch = temp.resolve("external-modification.json");
		Files.writeString(patch, Weaver.GSON.toJson(VaultContext.modification("blobs", empty(), Set.of("strict", "vendor:custom"), Set.of("inherited"))));
		var modified = vault.load(ARTIFACT, patch);
		assertSame(vault, modified.getVault());
		assertEquals("blobs", modified.getParent().orElseThrow().getContextName());
		assertThrows(IllegalStateException.class, () -> modified.getChild("any"));
		assertFalse(vault.hasChrysalis(ARTIFACT, "external-modification"));
		var saved = modified.saveAs("modded");
		assertEquals(Set.of("strict", "vendor:custom"), saved.getLocalTags());
		assertEquals(Set.of("inherited"), saved.getRemovedTags());
		assertEquals("blobs", saved.getParent().orElseThrow().getContextName());
		assertEquals("modification", Weaver.GSON.fromJson(Files.readString(saved.getChrysalisFile()), com.google.gson.JsonObject.class).get("type").getAsString());
		assertEquals(saved.getChrysalis(), vault.load(ARTIFACT, saved.getChrysalisFile()).getChrysalis());
		assertEquals("modded", vault.chrysalisize(ARTIFACT).getChild("modded").getContextName());
	}

	@Test void externalFilenameMatchingItsNamedParentDoesNotCreateFalseCycle() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		vault.saveModification(ARTIFACT, "modded", null, empty());
		Path external = temp.resolve("modded.json");
		Files.writeString(external, Weaver.GSON.toJson(VaultContext.modification("modded", empty(), Set.of(), Set.of())));
		var loaded = vault.load(ARTIFACT, external);
		assertEquals("modded", loaded.getParent().orElseThrow().getContextName());
		assertNotEquals(loaded.getChrysalisFile(), loaded.getRoot().getChrysalisFile());
		assertThrows(DatabaseVaultCircularityError.class, () -> loaded.saveAs("modded", true));
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
