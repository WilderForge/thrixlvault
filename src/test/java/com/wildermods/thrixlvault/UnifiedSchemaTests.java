package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.thrixlvault.steam.IVaultable;

class UnifiedSchemaTests {
	@TempDir Path temp;
	private static final IVaultable ARTIFACT = ChrysalisNavigationTests.ARTIFACT;
	private static final Set<String> FIELDS = Set.of("schema", "parent", "algorithm", "blobs", "replacements", "removals", "ignore", "removeIgnore", "tags", "removeTags");
	private JsonObject json(Object value) { return Weaver.GSON.toJsonTree(value).getAsJsonObject(); }
	private void sameFormat(JsonObject object) {
		assertEquals(FIELDS, object.keySet());
		assertEquals(java.util.List.of("schema", "parent", "algorithm", "tags", "removeTags",
			"ignore", "removeIgnore", "removals", "replacements", "blobs"),
			new java.util.ArrayList<>(object.keySet()));
		assertEquals(3, object.get("schema").getAsInt());
		assertFalse(object.has("content"));
	}

	@Test void allPublicWritersAndBothVaultDeclarationsUseTheSameSchemaAndFields() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("one"), "original");
		Files.writeString(source.resolve("two"), "original");
		var snapshot = new Weaver(vault, ARTIFACT, source).tag("strict").ignore(Path.of("cache")).weave();
		Files.writeString(source.resolve("one"), "changed");
		var child = new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").removeIgnore(Path.of("cache"))
			.removeTag("strict").tag("modded").weave();
		var patch = child.getContext().orElseThrow().modification();
		for (Object value : new Object[] {snapshot.getChrysalis(), patch, snapshot.getContext().orElseThrow(), child.getContext().orElseThrow()}) sameFormat(json(value));
		var rootJson = Weaver.GSON.fromJson(Files.readString(snapshot.getChrysalisFile()), JsonObject.class);
		var childJson = Weaver.GSON.fromJson(Files.readString(child.getChrysalisFile()), JsonObject.class);
		sameFormat(rootJson); sameFormat(childJson);
		assertFalse(rootJson.has("type"));
		assertTrue(rootJson.get("parent").isJsonNull());
		assertTrue(rootJson.getAsJsonObject("replacements").isEmpty());
		assertEquals(2, rootJson.getAsJsonObject("blobs").entrySet().iterator().next().getValue().getAsJsonArray().size());
		assertTrue(childJson.getAsJsonObject("blobs").isEmpty());
		assertEquals(1, childJson.getAsJsonObject("replacements").size());
		var loaded = vault.load(ARTIFACT, child.getChrysalisFile());
		assertEquals(child.getChrysalis(), loaded.getChrysalis());
		assertEquals(Set.of("modded"), loaded.getTags());
		assertEquals("blobs", loaded.getParent().orElseThrow().getContextName());
		assertTrue(loaded.getChrysalis().getIgnoredPaths().isEmpty());
		loaded.verifyDirectory(source);
	}

	@Test void legacyRootSchemasUpgradeWithoutChangingExpectations() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Files.createDirectories(vault.getChrysalisFile(ARTIFACT).getParent());
		String hash = new BlobFactory().blob(new byte[] {1}).hash();
		String blobs = "{\"" + hash + "\":[\"file\",\"copy\"]}";
		String[] legacy = {
			blobs,
			"{\"schema\":0," + blobs.substring(1),
			"{\"schema\":1,\"blobs\":" + blobs + "}",
			"{\"blobs\":" + blobs + ",\"algorithm\":\"SHA-1\",\"schema\":2}"
		};
		for (int i = 0; i < legacy.length; i++) {
			String name = "legacy-" + i;
			Path file = vault.getChrysalisFile(ARTIFACT, name);
			Files.writeString(file, legacy[i]);
			var loaded = vault.chrysalisize(ARTIFACT, name);
			assertEquals(legacy[i], Files.readString(file), "Loading must not rewrite files");
			assertEquals(2, loaded.getChrysalis().blobs().size());
			assertTrue(loaded.getParent().isEmpty());
			assertEquals("SHA-1", loaded.getChrysalis().getBlobFactory().algorithm());
			var upgraded = loaded.saveAs(name, true);
			sameFormat(Weaver.GSON.fromJson(Files.readString(file), JsonObject.class));
			assertEquals(loaded.getChrysalis(), upgraded.getChrysalis());
			assertEquals(loaded.getTags(), upgraded.getTags());
		}
	}

	@Test void legacyRootsRejectDevelopmentEnvelopesAndParents() {
		for (String json : new String[] {
			"{\"schema\":1,\"blobs\":{},\"parent\":\"blobs\"}",
			"{\"schema\":2,\"algorithm\":\"SHA-1\",\"changes\":{}}",
			"{\"schema\":1,\"type\":\"snapshot\",\"content\":{}}",
			"{\"schema\":3,\"algorithm\":\"SHA-1\",\"blobs\":{},\"ignoredPaths\":[]}"
		}) assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(json, VaultContext.class));
	}

	@Test void emptySchemaZeroAndLegacySha256AreSupported() throws Exception {
		assertEquals(Chrysalis.EMPTY, Weaver.GSON.fromJson("{}", VaultContext.class).rootSnapshot());
		assertEquals(Chrysalis.EMPTY, Weaver.GSON.fromJson("{\"schema\":0}", VaultContext.class).rootSnapshot());
		var factory = new BlobFactory("SHA-256");
		String hash = factory.blob(new byte[] {1}).hash();
		var context = Weaver.GSON.fromJson("{\"blobs\":{\"" + hash
			+ "\":[\"file\"]},\"schema\":2,\"algorithm\":\"SHA-256\"}", VaultContext.class);
		assertNull(context.parent());
		assertEquals("SHA-256", context.modification().algorithm());
		assertEquals(hash, context.rootSnapshot().blobs().keySet().iterator().next().hash());
	}

	@Test void unifiedSchemaRejectsInvalidVersionsFieldsAndMixedPayloads() {
		JsonObject empty = json(VaultContext.snapshot(Chrysalis.EMPTY.clone(), Set.of()));
		for (String value : Set.of("-1", "4", "5", "99", "4.5", "\"4\"", "null", "true")) {
			var invalid = empty.deepCopy(); invalid.add("schema", com.google.gson.JsonParser.parseString(value));
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(invalid, VaultContext.class));
		}
		for (String required : Set.of("schema", "algorithm", "blobs", "replacements", "removals")) {
			var invalid = empty.deepCopy(); invalid.remove(required);
			assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(invalid, VaultContext.class));
		}
		var invalid = empty.deepCopy(); invalid.addProperty("unknown", 1);
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(invalid, VaultContext.class));
		var child = empty.deepCopy(); child.addProperty("parent", "blobs");
		assertEquals("blobs", Weaver.GSON.fromJson(child, VaultContext.class).parent());
		var typed = empty.deepCopy(); typed.addProperty("type", "snapshot");
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(typed, VaultContext.class));
		var malformedRemovals = empty.deepCopy(); malformedRemovals.add("removals", new JsonObject());
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(malformedRemovals, VaultContext.class));
		String duplicate = empty.toString().replace("\"schema\":3", "\"schema\":3,\"schema\":3");
		assertThrows(JsonParseException.class, () -> Weaver.GSON.fromJson(duplicate, VaultContext.class));
	}

	@Test void schema3PropertyOrderAndSha256RoundTripAreSupported() throws Exception {
		BlobFactory factory = new BlobFactory("SHA-256"); Hash hash = factory.blob(new byte[] {1});
		var modification = ChrysalisModification.builder("SHA-256").add(Path.of("copy"), hash).ignore(Path.of("cache")).build();
		JsonObject original = json(VaultContext.modification(null, modification, Set.of("vendor:custom"), Set.of("absent")));
		JsonObject reordered = new JsonObject();
		for (String field : new String[] {"replacements", "removals", "blobs", "ignore", "removeIgnore", "algorithm", "parent", "tags", "removeTags", "schema"}) reordered.add(field, original.get(field));
		var parsed = Weaver.GSON.fromJson(reordered, VaultContext.class);
		assertEquals("SHA-256", parsed.modification().algorithm());
		assertEquals(modification.changes(), parsed.modification().changes());
		assertEquals(original, json(parsed));
	}
}
