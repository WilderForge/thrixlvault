package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.DatabaseVaultCircularityError;
import com.wildermods.thrixlvault.steam.IVaultable;

class FluentWeaverTests {
	@TempDir Path temp;
	private static final IVaultable ARTIFACT = ChrysalisNavigationTests.ARTIFACT;
	private Path source() throws Exception {
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("file"), "active");
		Files.createDirectory(source.resolve("cache"));
		Files.writeString(source.resolve("cache/data"), "hidden");
		return source;
	}
	private Vault vault() throws Exception { return new Vault(temp.resolve("vault")); }

	@Test void defaultSnapshotCanDeclareIgnoresAndTagsAndChildrenRestoreThem() throws Exception {
		Vault vault = vault(); Path source = source();
		Weaver weaver = new Weaver(vault, ARTIFACT, source).ignore(Path.of("cache")).tag("strict", "vendor:custom");
		var base = weaver.weave();
		assertSame(base, weaver.getChrysalisizedVault());
		assertEquals("blobs", base.getContextName());
		assertTrue(base.getParent().isEmpty());
		assertEquals(Set.of("strict", "vendor:custom"), vault.chrysalisize(ARTIFACT).getTags());
		assertEquals(2, base.getChrysalis().blobs().size());
		assertEquals(1, base.getChrysalis().activeBlobs().size());
		var hiddenHash = base.getChrysalis().blobs().entries().stream()
			.filter(e -> e.getValue().equals(Path.of("cache/data"))).findFirst().orElseThrow().getKey();
		assertEquals("hidden", Files.readString(base.getBlobFile(hiddenHash)));
		var child = new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs")
			.removeIgnore(Path.of("cache")).removeTag("strict").tag("modded").weave();
		assertEquals(Set.of("modded", "vendor:custom"), child.getTags());
		assertFalse(child.getChrysalis().isIgnored(Path.of("cache/data")));
		assertEquals(2, child.getChrysalis().activeBlobs().size());
		assertEquals("blobs", child.getParent().orElseThrow().getContextName());
		child.verifyDirectory(source);
	}

	@Test void namedStandaloneSnapshotDoesNotImplicitlyInheritBlobsAndParentCanBeCleared() throws Exception {
		Vault vault = vault(); Path source = source();
		new Weaver(vault, ARTIFACT, source).tag("inherited").ignore(Path.of("cache")).weave();
		Files.delete(source.resolve("cache/data"));
		var standalone = new Weaver(vault, ARTIFACT, source, "standalone")
			.parent("blobs").parent(null).tag("local").removeTag("absent").weave();
		assertTrue(standalone.getContext().orElseThrow().isSnapshot());
		assertTrue(standalone.getParent().isEmpty());
		assertEquals(Set.of("local"), standalone.getTags());
		assertTrue(standalone.getChrysalis().getIgnoredPaths().isEmpty());
		assertEquals(1, standalone.getChrysalis().blobs().size());
	}

	@Test void metadataConflictsFailBeforeStoringBlobs() throws Exception {
		Vault vault = vault(); Path source = source();
		assertThrows(IllegalArgumentException.class, () -> new Weaver(vault, ARTIFACT, source)
			.tag("strict").removeTag("strict").weave());
		assertThrows(IllegalArgumentException.class, () -> new Weaver(vault, ARTIFACT, source)
			.ignore(Path.of("cache")).removeIgnore(Path.of("cache")).weave());
		assertThrows(IllegalArgumentException.class, () -> new Weaver(vault, ARTIFACT, source)
			.removeIgnore(Path.of("cache")).weave());
		assertFalse(vault.hasChrysalis(ARTIFACT));
		try (var files = Files.list(vault.blobDir)) { assertEquals(0, files.count()); }
	}

	@Test void defaultCannotHaveAParentAndSelfParentsAreDetectedBeforeIngestion() throws Exception {
		Vault vault = vault(); Path source = source();
		assertThrows(IllegalArgumentException.class, () -> new Weaver(vault, ARTIFACT, source).parent("missing").weave());
		assertThrows(DatabaseVaultCircularityError.class, () -> new Weaver(vault, ARTIFACT, source).name("self").parent("self").weave());
		try (var files = Files.list(vault.blobDir)) { assertEquals(0, files.count()); }
	}

	@Test void forceSnapshotRepairsBlobsAndReplacesMetadata() throws Exception {
		Vault vault = vault(); Path source = source();
		var base = new Weaver(vault, ARTIFACT, source).tag("old").weave();
		for (var hash : base.getChrysalis().blobs().keySet()) Files.writeString(base.getBlobFile(hash), "corrupt");
		var repaired = new Weaver(vault, ARTIFACT, source, true).ignore(Path.of("cache")).tag("new").weave();
		assertEquals(Set.of("new"), repaired.getTags());
		repaired.verifyDirectory(source);
		var restored = new Weaver(vault, ARTIFACT, source).name("restored").parent("blobs")
			.removeIgnore(Path.of("cache")).weave();
		restored.verifyDirectory(source);
	}

	@Test void forceChildDoesNotOverwriteCorruptSharedBlobsOrThePreviousDeclaration() throws Exception {
		Vault vault = vault(); Path source = source();
		var base = new Weaver(vault, ARTIFACT, source).weave();
		var child = new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").weave();
		String previous = Files.readString(child.getChrysalisFile());
		var hash = base.getChrysalis().blobs().keySet().iterator().next();
		Files.writeString(base.getBlobFile(hash), "corrupt");
		assertThrows(IntegrityException.class, () -> new Weaver(vault, ARTIFACT, source)
			.name("modded").parent("blobs").force(true).tag("new").weave());
		assertEquals(previous, Files.readString(child.getChrysalisFile()));
		assertEquals("corrupt", Files.readString(base.getBlobFile(hash)));
	}

	@Test void configurationIsImmutableDuringAndAfterWeaving() throws Exception {
		Vault vault = vault(); Path source = source(); AtomicBoolean checked = new AtomicBoolean();
		Weaver weaver = new Weaver(vault, ARTIFACT, source) {
			@Override protected ChrysalisizedVault weave(WeaveContext context) throws IOException, IntegrityException {
				assertThrows(UnsupportedOperationException.class, () -> context.tags().add("mutated"));
				assertThrows(UnsupportedOperationException.class, () -> context.ignores().clear());
				assertThrows(IllegalStateException.class, () -> name("changed"));
				assertThrows(IllegalStateException.class, () -> parent("changed"));
				assertThrows(IllegalStateException.class, () -> force(true));
				assertThrows(IllegalStateException.class, () -> ignore(Path.of("changed")));
				assertThrows(IllegalStateException.class, () -> removeIgnore(Path.of("changed")));
				assertThrows(IllegalStateException.class, () -> tag("changed"));
				assertThrows(IllegalStateException.class, () -> removeTag("changed"));
				assertThrows(IllegalStateException.class, () -> weave());
				checked.set(true);
				return super.weave(context);
			}
		};
		var result = weaver.tag("strict").ignore(Path.of("cache")).weave();
		assertTrue(checked.get());
		assertSame(result, weaver.getChrysalisizedVault());
		assertThrows(IllegalStateException.class, () -> weaver.tag("changed"));
		assertThrows(IllegalStateException.class, weaver::weave);
		weaver.verify();
	}

	@Test void failedWeavingCanRetryButItsConfigurationStaysFixed() throws Exception {
		Vault vault = vault(); Path source = temp.resolve("later");
		Weaver weaver = new Weaver(vault, ARTIFACT, source).name("retry").tag("local");
		assertThrows(IOException.class, weaver::weave);
		assertNull(weaver.getChrysalisizedVault());
		assertThrows(IllegalStateException.class, () -> weaver.name("changed"));
		Files.createDirectory(source); Files.writeString(source.resolve("file"), "data");
		var result = weaver.weave();
		assertEquals("retry", result.getContextName());
		assertEquals(Set.of("local"), result.getTags());
		assertSame(result, weaver.getChrysalisizedVault());
	}

	@Test void fluentInputsAreCopiedAndDuplicateRootsAreNormalized() throws Exception {
		Vault vault = vault(); Path source = source();
		Path[] roots = {Path.of("cache/.")}; String[] tags = {"strict"};
		Weaver weaver = new Weaver(vault, ARTIFACT, source).ignore(roots).tag(tags).ignore(Path.of("cache"));
		roots[0] = Path.of("file"); tags[0] = "changed";
		var result = weaver.weave();
		assertEquals(Set.of(Path.of("cache")), result.getChrysalis().getIgnoredPaths());
		assertEquals(Set.of("strict"), result.getTags());
	}
}
