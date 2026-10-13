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
import com.google.gson.Gson;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/** Writes schema 3 declarations and reads historical root schemas 0, 1 and 2. */
public final class VaultContextSerializer extends TypeAdapter<VaultContext> implements TypeAdapterFactory {
	static final int SCHEMA = 3;

	/** Routes supported Java values through the same declaration reader and writer. */
	@Override
	@SuppressWarnings("unchecked")
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
		Class<?> raw = type.getRawType();
		if (raw == VaultContext.class) return (TypeAdapter<T>) nullSafe();
		if (raw != Chrysalis.class && raw != ChrysalisModification.class) return null;
		return new TypeAdapter<T>() {
			@Override public void write(JsonWriter out, T value) throws IOException {
				VaultContext context = raw == Chrysalis.class
					? VaultContext.snapshot((Chrysalis) value, Set.of())
					: VaultContext.modification(null, (ChrysalisModification) value, Set.of(), Set.of());
				VaultContextSerializer.this.write(out, context);
			}
			@Override public T read(JsonReader in) throws IOException {
				VaultContext context = VaultContextSerializer.this.read(in);
				if (raw == ChrysalisModification.class) return (T) context.modification();
				try {
					return (T) context.rootSnapshot();
				} catch (IllegalArgumentException | IllegalStateException e) {
					throw new JsonParseException("Cannot read an unresolved declaration as Chrysalis", e);
				}
			}
		}.nullSafe();
	}

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
			int version = data.has("schema") ? schemaVersion(data.remove("schema")) : 0;
			return switch (version) {
				case 0, 1, 2 -> readLegacyRoot(data, version);
				case SCHEMA -> readUnified(data);
				default -> throw new JsonParseException("Unsupported vault schema: " + version);
			};
		} catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
			throw new JsonParseException("Invalid vault context: " + e.getMessage(), e);
		}
	}

	private VaultContext readLegacyRoot(JsonObject data, int version) throws IOException {
		String algorithm = version == 2 ? optionalString(data.remove("algorithm")) : "SHA-1";
		JsonElement blobs = version == 0 ? data : data.remove("blobs");
		if (algorithm == null || blobs == null || !blobs.isJsonObject()) {
			throw new JsonParseException("Legacy schema " + version + " requires "
				+ (version == 2 ? "algorithm and blobs" : "blobs"));
		}
		if (version != 0 && !data.isEmpty()) {
			throw new JsonParseException("Unknown legacy root fields: " + data.keySet());
		}
		BlobFactory factory;
		try {
			factory = new BlobFactory(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new JsonParseException("Unsupported algorithm: " + algorithm, e);
		}
		var builder = ChrysalisModification.builder(algorithm);
		readGroups(blobs.getAsJsonObject(), factory).forEach(builder::add);
		return VaultContext.modification(null, builder.build(), Set.of(), Set.of());
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
			throw new JsonParseException("Schema 3 requires algorithm, blobs, replacements and removals");
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
		if (in.peek() == JsonToken.BEGIN_OBJECT) {
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
		if (in.peek() == JsonToken.BEGIN_ARRAY) {
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
