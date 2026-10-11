package com.wildermods.thrixlvault;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/** Reads typed declarations and legacy untyped Chrysalis manifests. */
final class VaultContextSerializer extends TypeAdapter<VaultContext> {
	private static final int SCHEMA = 1;

	@Override
	public void write(JsonWriter out, VaultContext value) throws IOException {
		JsonObject payload = value.isSnapshot()
			? Weaver.GSON.toJsonTree(value.snapshot().orElseThrow(), Chrysalis.class).getAsJsonObject()
			: Weaver.GSON.toJsonTree(value.modification().orElseThrow(), ChrysalisModification.class).getAsJsonObject();
		out.beginObject();
		out.name("schema").value(SCHEMA);
		out.name("type").value(value.isSnapshot() ? "snapshot" : "modification");
		if (!value.isSnapshot() && value.parent() != null) {
			out.name("parent").value(value.parent());
		}
		writeTags(out, "tags", value.tags());
		if (!value.isSnapshot()) {
			writeTags(out, "removeTags", value.removeTags());
		}
		out.name("content");
		Weaver.GSON.toJson(payload, out);
		out.endObject();
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
			String type = optionalString(data.remove("type"));
			String parent = optionalString(data.remove("parent"));
			Set<String> tags = readTags(data.remove("tags"));
			Set<String> removeTags = readTags(data.remove("removeTags"));
			if (data.has("content")) {
				JsonElement schema = data.remove("schema");
				if (schema == null || !schema.isJsonPrimitive()
					|| !schema.getAsJsonPrimitive().isNumber()) {
					throw new JsonParseException("Vault context requires a numeric schema");
				}
				int version;
				try {
					version = schema.getAsBigDecimal().intValueExact();
				} catch (ArithmeticException | NumberFormatException e) {
					throw new JsonParseException("Invalid context schema: " + schema, e);
				}
				if (version != SCHEMA) {
					throw new JsonParseException("Unsupported context schema: " + version);
				}
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
					Weaver.GSON.fromJson(data, ChrysalisModification.class), tags, removeTags);
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
			try (JsonReader reader = new JsonReader(new StringReader(ordered.toString()))) {
				return VaultContext.snapshot(new ChrysalisSerializer().read(reader), tags);
			}
		} catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
			throw new JsonParseException("Invalid vault context: " + e.getMessage(), e);
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
