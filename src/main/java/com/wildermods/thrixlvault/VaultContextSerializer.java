package com.wildermods.thrixlvault;

import java.io.IOException;
import java.util.HashSet;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.internal.bind.JsonTreeReader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/** One file schema for snapshots and modifications, with readers for historical formats. */
final class VaultContextSerializer extends TypeAdapter<VaultContext> {
	static final int SCHEMA = 4;

	@Override
	public void write(JsonWriter out, VaultContext value) throws IOException {
		Map<String, Set<String>> blobs = new TreeMap<>();
		Map<String, Set<String>> replacements = new TreeMap<>();
		Set<Path> removals = new TreeSet<>();
		ChrysalisModification modification = value.modification();
		String algorithm = modification.algorithm();
		Set<Path> ignores = modification.ignoredPaths();
		Set<Path> removeIgnores = modification.removedIgnores();
		modification.changes().forEach((path, change) -> {
			switch (change.operation()) {
				case ADD -> group(blobs, change.replacement(), path);
				case REPLACE -> group(replacements, change.replacement(), path);
				case REMOVE -> removals.add(path);
			}
		});
		out.beginObject();
		out.name("schema").value(SCHEMA);
		boolean serializeNulls = out.getSerializeNulls();
		try {
			out.setSerializeNulls(true);
			out.name("parent").value(value.parent());
		} finally {
			out.setSerializeNulls(serializeNulls);
		}
		out.name("algorithm").value(algorithm);
		writeTags(out, "tags", value.tags());
		writeTags(out, "removeTags", value.removeTags());
		writePaths(out, "ignore", ignores);
		writePaths(out, "removeIgnore", removeIgnores);
		writePaths(out, "removals", removals);
		writeGroups(out, "replacements", replacements);
		writeGroups(out, "blobs", blobs);
		out.endObject();
	}

	private static void group(Map<String, Set<String>> groups, Hash hash, Path path) {
		groups.computeIfAbsent(hash.hash(), key -> new TreeSet<>()).add(jsonPath(path));
	}

	private static String jsonPath(Path path) {
		return path.toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	private static void writeGroups(JsonWriter out, String field, Map<String, Set<String>> groups) throws IOException {
		out.name(field).beginObject();
		for (var entry : groups.entrySet()) {
			out.name(entry.getKey()).beginArray();
			for (String path : entry.getValue()) out.value(path);
			out.endArray();
		}
		out.endObject();
	}

	private static void writePaths(JsonWriter out, String field, Set<Path> paths) throws IOException {
		out.name(field).beginArray();
		for (String path : paths.stream().map(VaultContextSerializer::jsonPath).sorted().toList()) out.value(path);
		out.endArray();
	}

	@Override
	public VaultContext read(JsonReader in) throws IOException {
		JsonObject data = new JsonObject();
		in.beginObject();
		while (in.hasNext()) {
			String name = in.nextName();
			if (data.has(name)) {
				throw new JsonParseException("Duplicate context field: " + name);
			}
			data.add(name, readValue(in));
		}
		in.endObject();
		try {
			Integer version = data.has("schema") ? schemaVersion(data.get("schema")) : null;
			if (version != null && version >= SCHEMA) {
				if (version != SCHEMA) throw new JsonParseException("Unsupported vault schema: " + version);
				return readUnified(data);
			}
			String type = optionalString(data.remove("type"));
			String parent = optionalString(data.remove("parent"));
			Set<String> tags = readTags(data.remove("tags"));
			Set<String> removeTags = readTags(data.remove("removeTags"));
			if (data.has("content")) {
				if (version == null || version != 1) {
					throw new JsonParseException("Unsupported legacy envelope schema: " + version);
				}
				data.remove("schema");
				if (type == null) {
					throw new JsonParseException("Versioned context requires an explicit type");
				}
				JsonElement content = data.remove("content");
				if (!content.isJsonObject()) {
					throw new JsonParseException("Context content must be an object");
				}
				if (!data.isEmpty()) {
					throw new JsonParseException("Unknown context fields: " + data.keySet());
				}
				data = content.getAsJsonObject();
			}
			// Without 'content', this is the previous flat context format or a
			// legacy full manifest: its schema belongs to the payload, not the context.
			if (type == null) {
				type = data.has("changes") ? "modification" : "snapshot";
			}
			if (type.equals("modification")) {
				return VaultContext.modification(parent,
					new ChrysalisModificationSerializer().readPayload(new JsonTreeReader(data)), tags, removeTags);
			}
			if (!type.equals("snapshot")) {
				throw new JsonParseException("Unknown context type: " + type);
			}
			if (parent != null || !removeTags.isEmpty()) {
				throw new JsonParseException("Full snapshots cannot have parents or tag removals");
			}
			// The legacy Chrysalis adapter expects schema/algorithm/blobs in order.
			JsonObject ordered = new JsonObject();
			for (String field : new String[] {"schema", "algorithm", "blobs", "ignoredPaths"}) {
				if (data.has(field)) { ordered.add(field, data.remove(field)); }
			}
			if (ordered.has("schema") && !data.isEmpty()) {
				throw new JsonParseException("Unknown snapshot fields: " + data.keySet());
			}
			for (var field : data.entrySet()) { ordered.add(field.getKey(), field.getValue()); }
			try (JsonReader reader = new JsonTreeReader(ordered)) {
				return VaultContext.snapshot(new ChrysalisSerializer().readPayload(reader), tags);
			}
		} catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
			throw new JsonParseException("Invalid vault context: " + e.getMessage(), e);
		}
	}

	private VaultContext readUnified(JsonObject data) throws IOException {
		data.remove("schema");
		String parent = optionalString(data.remove("parent"));
		String algorithm = optionalString(data.remove("algorithm"));
		Set<String> tags = readTags(data.remove("tags"));
		Set<String> removeTags = readTags(data.remove("removeTags"));
		JsonElement blobs = data.remove("blobs");
		JsonElement replacements = data.remove("replacements");
		JsonElement removals = data.remove("removals");
		Set<Path> ignores = readPaths(data.remove("ignore"));
		Set<Path> removedIgnores = readPaths(data.remove("removeIgnore"));
		if (algorithm == null || blobs == null || !blobs.isJsonObject()
			|| replacements == null || !replacements.isJsonObject() || removals == null || !removals.isJsonArray()) {
			throw new JsonParseException("Schema 4 requires algorithm, blobs, replacements and removals");
		}
		if (!data.isEmpty()) throw new JsonParseException("Unknown vault fields: " + data.keySet());
		BlobFactory factory;
		try {
			factory = new BlobFactory(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new JsonParseException("Unsupported algorithm: " + algorithm, e);
		}
		var builder = ChrysalisModification.builder(algorithm);
		readGroups(blobs.getAsJsonObject(), factory).forEach(builder::add);
		readGroups(replacements.getAsJsonObject(), factory).forEach(builder::replace);
		readPaths(removals).forEach(builder::remove);
		ignores.forEach(builder::ignore);
		removedIgnores.forEach(builder::removeIgnore);
		ChrysalisModification modification = builder.build();
		return VaultContext.modification(parent, modification, tags, removeTags);
	}

	private static Map<Path, Hash> readGroups(JsonObject groups, BlobFactory factory) {
		Map<Path, Hash> files = new TreeMap<>();
		for (var entry : groups.entrySet()) {
			Hash hash = Hash.of(factory.digest, entry.getKey());
			for (Path path : readPaths(entry.getValue())) {
				if (files.putIfAbsent(path, hash) != null) throw new JsonParseException("Multiple hashes for path: " + path);
			}
		}
		return files;
	}

	private static Set<Path> readPaths(JsonElement element) {
		if (element == null) return Set.of();
		if (!element.isJsonArray()) throw new JsonParseException("Paths must be arrays");
		Set<Path> paths = new TreeSet<>();
		for (JsonElement entry : element.getAsJsonArray()) {
			String text = optionalString(entry);
			if (text == null || text.contains("\\")) throw new JsonParseException("Expected a relative path using '/'");
			Path path = ChrysalisModification.normalizePath(Path.of(text));
			if (!paths.add(path)) throw new JsonParseException("Duplicate path: " + path);
		}
		return paths;
	}

	private static int schemaVersion(JsonElement schema) {
		if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()) {
			throw new JsonParseException("Vault requires a numeric schema");
		}
		try {
			return schema.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			throw new JsonParseException("Invalid vault schema: " + schema, e);
		}
	}

	// Preserve duplicate-key validation within nested changes instead of silently
	// accepting the last value while buffering fields for order-independent reading.
	private static JsonElement readValue(JsonReader in) throws IOException {
		if (in.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
			JsonObject object = new JsonObject();
			in.beginObject();
			while (in.hasNext()) {
				String name = in.nextName();
				if (object.has(name)) { throw new JsonParseException("Duplicate field: " + name); }
				object.add(name, readValue(in));
			}
			in.endObject();
			return object;
		}
		if (in.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
			var array = new com.google.gson.JsonArray();
			in.beginArray();
			while (in.hasNext()) { array.add(readValue(in)); }
			in.endArray();
			return array;
		}
		return JsonParser.parseReader(in);
	}

	private static String optionalString(JsonElement element) {
		if (element == null || element.isJsonNull()) { return null; }
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new JsonParseException("Expected a string or null");
		}
		return element.getAsString();
	}
	private static Set<String> readTags(JsonElement element) {
		if (element == null) { return Set.of(); }
		if (!element.isJsonArray()) { throw new JsonParseException("Tags must be arrays of strings"); }
		Set<String> tags = new HashSet<>();
		for (JsonElement tag : element.getAsJsonArray()) {
			String text = optionalString(tag);
			if (text == null || !tags.add(text)) { throw new JsonParseException("Null or duplicate tag"); }
		}
		return tags;
	}
	private static void writeTags(JsonWriter out, String name, Set<String> tags) throws IOException {
		out.name(name).beginArray();
		for (String tag : tags.stream().sorted().toList()) { out.value(tag); }
		out.endArray();
	}
}
