package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.gson.JsonParseException;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.thrixlvault.ChrysalisModification.Change;
import com.wildermods.thrixlvault.ChrysalisModification.Operation;
import com.wildermods.thrixlvault.steam.IVaultable;

class ChrysalisModificationTests {

	private final BlobFactory factory = new BlobFactory();
	private final Hash original = hash("original");
	private final Hash replacement = hash("replacement");

	@TempDir Path temp;

	@Test
	void fiftyThousandCopiesRemainIndependent() {
		Chrysalis base = snapshot(Map.of());
		SetMultimap<Hash, Path> files = HashMultimap.create();
		var builder = ChrysalisModification.builder(factory.algorithm());
		for (int i = 0; i < 50_000; i++) {
			Path path = Path.of("copies", "file-" + i);
			files.put(original, path);
			if (i % 3 == 0) {
				builder.replace(path, original, replacement);
			} else if (i % 3 == 1) {
				builder.remove(path, original);
			}
		}
		for (int i = 0; i < 1_000; i++) {
			builder.add(Path.of("added", "file-" + i), replacement);
		}
		base.setBlobs(files);
		Chrysalis result = builder.build().applyTo(base);
		assertEquals(50_000, base.blobs().size());
		assertEquals(34_333, result.blobs().size());
		assertEquals(16_666, result.blobs().get(original).size());
		assertEquals(17_667, result.blobs().get(replacement).size());
		for (int i = 0; i < 50_000; i++) {
			Path path = Path.of("copies", "file-" + i);
			assertEquals(i % 3 == 2, result.blobs().containsEntry(original, path));
			assertEquals(i % 3 == 0, result.blobs().containsEntry(replacement, path));
		}
	}

	@Test
	void sharedHashesAreChangedOnlyAtTheirSelectedPaths() {
		Chrysalis base = snapshot(Map.of(
			"keep.jar", original, "replace-a.jar", original, "replace-b.jar", original,
			"remove-a.jar", original, "remove-b.jar", original));
		Chrysalis before = base.clone();
		ChrysalisModification modification = ChrysalisModification.builder(factory.algorithm())
			.replace(Path.of("replace-a.jar"), original, replacement)
			.replace(Path.of("replace-b.jar"), original, replacement)
			.remove(Path.of("remove-a.jar"), original)
			.remove(Path.of("remove-b.jar"), original)
			.add(Path.of("add-a.jar"), replacement)
			.add(Path.of("add-b.jar"), replacement)
			.add(Path.of("copy.jar"), original)
			.build();

		Chrysalis result = modification.applyTo(base);
		assertEquals(snapshot(Map.of("keep.jar", original, "copy.jar", original,
			"replace-a.jar", replacement, "replace-b.jar", replacement,
			"add-a.jar", replacement, "add-b.jar", replacement)), result);
		assertEquals(before, base);
		assertEquals(2, result.blobs().get(original).size());
		assertEquals(4, result.blobs().get(replacement).size());
		assertThrows(UnsupportedOperationException.class, () -> result.blobs().clear());
		assertEquals(before, base);
	}

	@Test
	void failedPreconditionsLeaveTheBaseUntouched() {
		Chrysalis base = snapshot(Map.of("file", original));
		for (ChrysalisModification modification : List.of(
			ChrysalisModification.builder(factory.algorithm()).add(Path.of("file"), replacement).build(),
			ChrysalisModification.builder(factory.algorithm()).replace(Path.of("file"), replacement, original).build(),
			ChrysalisModification.builder(factory.algorithm()).remove(Path.of("file"), replacement).build(),
			ChrysalisModification.builder(factory.algorithm()).remove(Path.of("absent"), original).build(),
			ChrysalisModification.builder(factory.algorithm()).replace(Path.of("absent"), original, replacement).build(),
			ChrysalisModification.builder(factory.algorithm()).add(Path.of("a"), replacement)
				.remove(Path.of("file"), replacement).build())) {
			assertThrows(IllegalArgumentException.class, () -> modification.applyTo(base));
			assertEquals(snapshot(Map.of("file", original)), base);
		}
	}

	@Test
	void pathsAndChangesAreValidated() {
		for (String path : List.of("", ".", "..", "../escape", "/absolute", "C:/absolute", "a\\b")) {
			if (path.equals("a\\b") && Path.of("a").getFileSystem().getSeparator().equals("\\")) {
				continue;
			}
			assertThrows(IllegalArgumentException.class, () ->
				ChrysalisModification.builder(factory.algorithm()).add(Path.of(path), original));
		}
		var builder = ChrysalisModification.builder(factory.algorithm()).add(Path.of("a/../file"), original);
		assertThrows(IllegalArgumentException.class, () -> builder.add(Path.of("file"), replacement));
		assertThrows(IllegalArgumentException.class, () -> new Change(Operation.ADD, original, replacement));
		assertThrows(IllegalArgumentException.class, () -> new Change(Operation.REMOVE, original, replacement));
		assertThrows(NullPointerException.class, () -> new Change(Operation.REPLACE, null, replacement));
		assertThrows(UnsupportedOperationException.class, () -> builder.build().changes().clear());
		assertEquals(1, builder.build().changes().size());
		ChrysalisModification built = builder.build();
		builder.add(Path.of("other"), original);
		assertEquals(1, built.changes().size());
	}

	@Test
	void rejectsAmbiguousAndConflictingFileExpectations() {
		Chrysalis base = snapshot(Map.of("file", original));
		SetMultimap<Hash, Path> ambiguous = HashMultimap.create(base.blobs());
		ambiguous.put(replacement, Path.of("file"));
		base.setBlobs(ambiguous);
		ChrysalisModification empty = ChrysalisModification.builder(factory.algorithm()).build();
		assertThrows(IllegalArgumentException.class, () -> empty.applyTo(base));
		var conflict = ChrysalisModification.builder(factory.algorithm())
			.add(Path.of("file/child"), replacement).build();
		assertThrows(IllegalArgumentException.class, () -> conflict.applyTo(snapshot(Map.of("file", original))));
		var valid = ChrysalisModification.builder(factory.algorithm())
			.remove(Path.of("file"), original).add(Path.of("file/child"), replacement).build();
		assertEquals(snapshot(Map.of("file/child", replacement)), valid.applyTo(snapshot(Map.of("file", original))));
	}

	@Test
	void completeSnapshotDiffRoundTripsWithRepeatedHashes() {
		Chrysalis base = snapshot(Map.of("a", original, "b", original, "c", original));
		Chrysalis target = snapshot(Map.of("a", original, "b", replacement, "d", original, "e", original));
		ChrysalisModification diff = ChrysalisModification.between(base, target);
		assertEquals(4, diff.changes().size());
		assertEquals(target, diff.applyTo(base));
		assertTrue(ChrysalisModification.between(base, base).changes().isEmpty());
		assertEquals(Chrysalis.EMPTY, ChrysalisModification.between(base, Chrysalis.EMPTY).applyTo(base));
		assertEquals(base, ChrysalisModification.between(Chrysalis.EMPTY, base).applyTo(Chrysalis.EMPTY));
	}

	@Test
	void jsonAndFilesPreserveSharedHashesAndIgnorePropertyOrder() throws Exception {
		Chrysalis base = snapshot(Map.of("a", original, "b", original, "c", original));
		Chrysalis target = snapshot(Map.of("a", replacement, "b", replacement,
			"dir/d", original, "dir/e", original));
		ChrysalisModification diff = ChrysalisModification.between(base, target);
		String json = Weaver.GSON.toJson(diff);
		ChrysalisModification parsed = Weaver.GSON.fromJson(json, ChrysalisModification.class);
		assertEquals(target, parsed.applyTo(base));
		assertEquals(json, Weaver.GSON.toJson(parsed));
		Path file = temp.resolve("classpath_fix.json");
		diff.toFile(file);
		assertEquals(target, ChrysalisModification.fromFile(file).applyTo(base));
		String reordered = "{\"changes\":{\"new\":{\"replacement\":\"" + original.hash()
			+ "\",\"operation\":\"add\"}},\"algorithm\":\"SHA-1\",\"schema\":1}";
		assertEquals(snapshot(Map.of("new", original)), Weaver.GSON.fromJson(reordered,
			ChrysalisModification.class).applyTo(Chrysalis.EMPTY));
	}

	@Test
	void malformedJsonIsRejectedRatherThanDroppingChanges() {
		String addition = "{\"operation\":\"add\",\"replacement\":\"" + original.hash() + "\"}";
		for (String json : List.of(
			"{\"schema\":3,\"algorithm\":\"SHA-1\",\"changes\":{}}",
			"{\"schema\":1,\"changes\":{}}",
			"{\"schema\":1,\"schema\":1,\"algorithm\":\"SHA-1\",\"changes\":{}}",
			"{\"schema\":1,\"algorithm\":\"SHA-1\",\"changes\":{\"x\":" + addition + ",\"x\":" + addition + "}}",
			"{\"schema\":1,\"algorithm\":\"SHA-1\",\"changes\":{\"x\":" + addition + ",\"a/../x\":" + addition + "}}",
			"{\"schema\":1,\"algorithm\":\"SHA-1\",\"changes\":{\"../x\":" + addition + "}}",
			"{\"schema\":1,\"algorithm\":\"SHA-1\",\"changes\":{\"x\":{\"operation\":\"remove\"}}}",
			"{\"schema\":1,\"algorithm\":\"SHA-1\",\"changes\":{\"x\":{\"operation\":\"unknown\"}}}")) {
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(json, ChrysalisModification.class));
		}
	}

	@Test
	void nonDefaultAlgorithmsSurviveSerializationAndRejectMismatches() throws Exception {
		BlobFactory sha256 = new BlobFactory("SHA-256");
		Hash hash = sha256.blob("value".getBytes(StandardCharsets.UTF_8));
		var modification = ChrysalisModification.builder("SHA-256").add(Path.of("file"), hash).build();
		var parsed = Weaver.GSON.fromJson(Weaver.GSON.toJson(modification), ChrysalisModification.class);
		Chrysalis base = snapshot(Map.of()).factory(sha256);
		Chrysalis result = parsed.applyTo(base);
		assertEquals("SHA-256", result.getBlobFactory().algorithm());
		assertTrue(result.blobs().containsEntry(hash, Path.of("file")));
		assertThrows(IllegalArgumentException.class, () -> parsed.applyTo(Chrysalis.EMPTY));
		assertThrows(IllegalArgumentException.class, () -> ChrysalisModification.builder("SHA-256")
			.add(Path.of("file"), original).build());
		assertThrows(IllegalArgumentException.class, () -> ChrysalisModification.between(Chrysalis.EMPTY, result));
	}

	@Test
	void vaultViewsApplyOrderedChangesAndExportTheResolvedFiles() throws Exception {
		IVaultable artifact = new IVaultable() {
			public String name() { return "test"; }
			public Path artifactPath() { return Path.of("test"); }
		};
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("keep"), "original");
		Files.writeString(source.resolve("change"), "original");
		Files.writeString(source.resolve("remove"), "original");
		Vault parent = new Vault(temp.resolve("vault"));
		ChrysalisizedVault vanilla = new Weaver(parent, artifact, source).weave();
		Files.write(parent.getBlobDir().resolve(replacement.hash()), "replacement".getBytes(StandardCharsets.UTF_8));
		var first = ChrysalisModification.builder(factory.algorithm())
			.replace(Path.of("change"), original, replacement)
			.remove(Path.of("remove"), original)
			.add(Path.of("added"), replacement).build();
		var second = ChrysalisModification.builder(factory.algorithm())
			.replace(Path.of("change"), replacement, original).build();
		ChrysalisizedVault modded = vanilla.withModifications("modded", first, second);
		assertEquals(parent.getChrysalisFile(artifact, "modded"), modded.getChrysalisFile());
		assertFalse(modded.hasChrysalis());
		assertTrue(vanilla.hasChrysalis());
		assertEquals(3, vanilla.getChrysalis().blobs().size());
		assertTrue(vanilla.getChrysalis().blobs().containsEntry(original, Path.of("remove")));
		Path export = temp.resolve("export");
		modded.export(export, true);
		assertEquals("original", Files.readString(export.resolve("keep")));
		assertEquals("original", Files.readString(export.resolve("change")));
		assertEquals("replacement", Files.readString(export.resolve("added")));
		assertFalse(Files.exists(export.resolve("remove")));
		assertThrows(IllegalArgumentException.class, () -> vanilla.withModifications("wrong", second, first));
		ChrysalisizedVault copied = vanilla.withModifications("copy");
		assertThrows(UnsupportedOperationException.class, () -> copied.getChrysalis().blobs().clear());
		assertEquals(3, vanilla.getChrysalis().blobs().size());
	}

	@Test
	void cloneKeepsHashPathAssociationsAndNamedManifestsResolveCorrectly() throws Exception {
		Chrysalis originalSnapshot = snapshot(Map.of("a", original, "b", original, "c", replacement));
		Chrysalis clone = originalSnapshot.clone();
		assertEquals(originalSnapshot, clone);
		assertEquals(3, clone.blobs().size());
		assertThrows(UnsupportedOperationException.class, () -> clone.blobs().remove(original, Path.of("a")));
		assertTrue(originalSnapshot.blobs().containsEntry(original, Path.of("a")));
		IVaultable artifact = new IVaultable() {
			public String name() { return "test"; }
			public Path artifactPath() { return Path.of("test"); }
		};
		Vault vault = new Vault(temp.resolve("named-vault"));
		Path named = vault.getChrysalisFile(artifact, "named");
		Files.createDirectories(named.getParent());
		Files.writeString(named, Weaver.GSON.toJson(originalSnapshot));
		assertTrue(vault.hasChrysalis(artifact, "named"));
		ChrysalisizedVault loaded = vault.chrysalisize(artifact, "named");
		assertTrue(loaded.hasChrysalis());
		assertEquals(named, loaded.getChrysalisFile());
	}

	private Hash hash(String text) {
		return factory.blob(text.getBytes(StandardCharsets.UTF_8));
	}

	@SuppressWarnings("deprecation")
	private Chrysalis snapshot(Map<String, Hash> files) {
		SetMultimap<Hash, Path> blobs = HashMultimap.create();
		files.forEach((path, hash) -> blobs.put(hash, Path.of(path)));
		Chrysalis chrysalis = new Chrysalis();
		chrysalis.factory(factory);
		chrysalis.setBlobs(Multimaps.synchronizedSetMultimap(blobs));
		return chrysalis;
	}
}
