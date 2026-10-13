package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import com.google.common.collect.HashMultimap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;

class GroupedFileSchemaTests {
	private final BlobFactory factory = new BlobFactory();
	private final Hash old = factory.blob(new byte[] {1});
	private final Hash replacement = factory.blob(new byte[] {2});
	private Chrysalis base(String... paths) {
		var blobs = HashMultimap.<Hash, Path>create();
		for (String path : paths) blobs.put(old, Path.of(path));
		Chrysalis base = Chrysalis.EMPTY.clone().factory(factory);
		base.setBlobs(blobs);
		return base;
	}
	private JsonObject json(Object value) { return Weaver.GSON.toJsonTree(value).getAsJsonObject(); }

	@Test void oneHashCanAddAndReplaceMultiplePathsWhileRemovalsContainOnlyPaths() {
		Chrysalis base = base("replace-a", "replace-b", "remove-a", "remove-b");
		var patch = ChrysalisModification.builder("SHA-1")
			.add(Path.of("add-b"), replacement).add(Path.of("add-a"), replacement)
			.replace(Path.of("replace-b"), old, replacement).replace(Path.of("replace-a"), old, replacement)
			.remove(Path.of("remove-b"), old).remove(Path.of("remove-a"), old).build();
		JsonObject serialized = json(patch);
		assertEquals(4, serialized.get("schema").getAsInt());
		assertFalse(serialized.has("changes")); assertFalse(serialized.has("additions"));
		assertEquals("[\"add-a\",\"add-b\"]", serialized.getAsJsonObject("blobs").get(replacement.hash()).toString());
		assertEquals("[\"replace-a\",\"replace-b\"]", serialized.getAsJsonObject("replacements").get(replacement.hash()).toString());
		assertEquals("[\"remove-a\",\"remove-b\"]", serialized.getAsJsonArray("removals").toString());
		assertFalse(serialized.toString().contains(old.hash()));
		var loaded = Weaver.GSON.fromJson(serialized, ChrysalisModification.class);
		assertEquals(patch.applyTo(base), loaded.applyTo(base));
		assertEquals(4, loaded.applyTo(base).blobs().get(replacement).size());
	}

	@Test void snapshotAndModificationHashKeysAndPathArraysAreAlwaysSorted() {
		Chrysalis snapshot = base("z", "a");
		var unsorted = HashMultimap.<Hash, Path>create(snapshot.blobs());
		unsorted.put(replacement, Path.of("y")); unsorted.put(replacement, Path.of("b"));
		snapshot.setBlobs(unsorted);
		JsonObject full = json(snapshot);
		var patch = ChrysalisModification.builder("SHA-1").add(Path.of("z"), old).add(Path.of("a"), old)
			.add(Path.of("y"), replacement).add(Path.of("b"), replacement)
			.replace(Path.of("rz"), old, replacement).replace(Path.of("ra"), old, replacement).build();
		JsonObject modded = json(patch);
		assertEquals(full.keySet(), modded.keySet());
		assertEquals(full.get("schema"), modded.get("schema"));
		for (JsonObject groups : new JsonObject[] {full.getAsJsonObject("blobs"), modded.getAsJsonObject("blobs"), modded.getAsJsonObject("replacements")}) {
			var keys = new ArrayList<>(groups.keySet());
			assertEquals(keys.stream().sorted().toList(), keys);
			for (var entry : groups.entrySet()) {
				var paths = new ArrayList<String>(); entry.getValue().getAsJsonArray().forEach(e -> paths.add(e.getAsString()));
				assertEquals(paths.stream().sorted().toList(), paths);
			}
		}
		assertTrue(full.getAsJsonObject("replacements").isEmpty());
		assertTrue(full.getAsJsonArray("removals").isEmpty());
	}

	@Test void loadedOperationsRequireExistingPathsAndExplicitBuilderHashesStillGuardChanges() {
		var checked = ChrysalisModification.builder("SHA-1").replace(Path.of("file"), old, replacement).build();
		Chrysalis different = ChrysalisModification.builder("SHA-1").add(Path.of("file"), replacement).build().applyTo(Chrysalis.EMPTY.clone());
		assertThrows(IllegalArgumentException.class, () -> checked.applyTo(different));
		var loaded = Weaver.GSON.fromJson(Weaver.GSON.toJson(checked), ChrysalisModification.class);
		assertEquals(different, loaded.applyTo(different));
		assertThrows(IllegalArgumentException.class, () -> loaded.applyTo(Chrysalis.EMPTY.clone()));
		var removal = Weaver.GSON.fromJson(Weaver.GSON.toJson(ChrysalisModification.builder("SHA-1").remove(Path.of("file"), old).build()), ChrysalisModification.class);
		assertTrue(removal.applyTo(different).blobs().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> removal.applyTo(Chrysalis.EMPTY.clone()));
		assertThrows(IllegalArgumentException.class, () -> ChrysalisModification.builder("SHA-1").add(Path.of("file"), old).build().applyTo(different));
	}

	@Test void groupedFormatRejectsDuplicateConflictingAndEscapingPaths() {
		JsonObject empty = json(ChrysalisModification.builder("SHA-1").build());
		for (String blobs : new String[] {
			"{\"" + old.hash() + "\":[\"same\",\"same\"]}",
			"{\"" + old.hash() + "\":[\"same\",\"a/../same\"]}",
			"{\"" + old.hash() + "\":[\"../escape\"]}",
			"{\"" + old.hash() + "\":[\"same\"],\"" + replacement.hash() + "\":[\"same\"]}"
		}) {
			var invalid = empty.deepCopy(); invalid.add("blobs", com.google.gson.JsonParser.parseString(blobs));
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(invalid, ChrysalisModification.class));
		}
		var conflict = empty.deepCopy();
		var group = com.google.gson.JsonParser.parseString("{\"" + old.hash() + "\":[\"same\"]}");
		conflict.add("blobs", group); conflict.add("replacements", group.deepCopy());
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(conflict, ChrysalisModification.class));
		var duplicateRemovals = empty.deepCopy(); duplicateRemovals.add("removals", com.google.gson.JsonParser.parseString("[\"same\",\"same\"]"));
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(duplicateRemovals, ChrysalisModification.class));
		var oldLayout = empty.deepCopy(); oldLayout.add("changes", new JsonObject());
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(oldLayout, ChrysalisModification.class));
	}
}
