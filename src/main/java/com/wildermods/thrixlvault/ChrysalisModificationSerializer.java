package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.thrixlvault.ChrysalisModification.Change;
import com.wildermods.thrixlvault.ChrysalisModification.Operation;

/** Serializes path-specific modifications independently of full Chrysalis manifests. */
public final class ChrysalisModificationSerializer extends TypeAdapter<ChrysalisModification> {

	private static final int SCHEMA = 2;

	@Override
	public void write(JsonWriter out, ChrysalisModification value) throws IOException {
		out.beginObject();
		out.name("schema").value(SCHEMA);
		out.name("algorithm").value(value.algorithm());
		out.name("changes").beginObject();
		for (Map.Entry<Path, Change> entry : value.changes().entrySet()) {
			Path path = entry.getKey();
			out.name(path.toString().replace(path.getFileSystem().getSeparator(), "/"));
			out.beginObject();
			Change change = entry.getValue();
			out.name("operation").value(change.operation().name().toLowerCase(Locale.ROOT));
			if (change.expected() != null) {
				out.name("expected").value(change.expected().hash());
			}
			if (change.replacement() != null) {
				out.name("replacement").value(change.replacement().hash());
			}
			out.endObject();
		}
		out.endObject();
		writePaths(out, "ignore", value.ignoredPaths());
		writePaths(out, "removeIgnore", value.removedIgnores());
		out.endObject();
	}

	@Override
	public ChrysalisModification read(JsonReader in) throws IOException {
		Integer schema = null;
		String algorithm = null;
		Map<Path, RawChange> raw = null;
		Set<Path> ignoredPaths = Set.of();
		Set<Path> removedIgnores = Set.of();
		Set<String> fields = new HashSet<>();
		in.beginObject();
		while (in.hasNext()) {
			String field = in.nextName();
			requireUnique(fields, field);
			switch (field) {
				case "schema" -> schema = in.nextInt();
				case "algorithm" -> algorithm = in.nextString();
				case "changes" -> raw = readChanges(in);
				case "ignore" -> ignoredPaths = readPaths(in);
				case "removeIgnore" -> removedIgnores = readPaths(in);
				default -> throw new JsonParseException("Unknown modification field: " + field);
			}
		}
		in.endObject();
		if (schema == null || schema < 1 || schema > SCHEMA) {
			throw new JsonParseException("Unsupported modification schema: " + schema);
		}
		if (schema == 1 && (fields.contains("ignore") || fields.contains("removeIgnore"))) {
			throw new JsonParseException("Ignore operations require modification schema 2");
		}
		if (algorithm == null || raw == null) {
			throw new JsonParseException("Modification requires algorithm and changes");
		}
		try {
			BlobFactory factory = new BlobFactory(algorithm);
			Map<Path, Change> changes = new HashMap<>();
			for (Map.Entry<Path, RawChange> entry : raw.entrySet()) {
				RawChange change = entry.getValue();
				changes.put(entry.getKey(), new Change(change.operation(),
					toHash(factory, change.expected()), toHash(factory, change.replacement())));
			}
			return new ChrysalisModification(algorithm, changes, ignoredPaths, removedIgnores);
		} catch (NoSuchAlgorithmException | IllegalArgumentException | NullPointerException e) {
			throw new JsonParseException("Invalid modification: " + e.getMessage(), e);
		}
	}

	private static Map<Path, RawChange> readChanges(JsonReader in) throws IOException {
		Map<Path, RawChange> changes = new HashMap<>();
		in.beginObject();
		while (in.hasNext()) {
			String text = in.nextName();
			Path path;
			try {
				// JSON paths always use '/', regardless of the writer's platform.
				if (text.contains("\\")) {
					throw new IllegalArgumentException("JSON paths must use '/': " + text);
				}
				path = ChrysalisModification.normalizePath(Path.of(text));
			} catch (IllegalArgumentException e) {
				throw new JsonParseException("Invalid modification path: " + text, e);
			}
			if (changes.containsKey(path)) {
				throw new JsonParseException("Multiple changes for path: " + path);
			}
			Operation operation = null;
			String expected = null;
			String replacement = null;
			Set<String> fields = new HashSet<>();
			in.beginObject();
			while (in.hasNext()) {
				String field = in.nextName();
				requireUnique(fields, field);
				switch (field) {
					case "operation" -> {
						String name = in.nextString();
						try {
							operation = Operation.valueOf(name.toUpperCase(Locale.ROOT));
						} catch (IllegalArgumentException e) {
							throw new JsonParseException("Unknown operation: " + name, e);
						}
					}
					case "expected" -> expected = in.nextString();
					case "replacement" -> replacement = in.nextString();
					default -> throw new JsonParseException("Unknown change field: " + field);
				}
			}
			in.endObject();
			changes.put(path, new RawChange(operation, expected, replacement));
		}
		in.endObject();
		return changes;
	}

	private static Hash toHash(BlobFactory factory, String hash) {
		return hash == null ? null : Hash.of(factory.digest, hash);
	}

	private static void writePaths(JsonWriter out, String name, Set<Path> paths) throws IOException {
		out.name(name).beginArray();
		for (Path path : paths) {
			out.value(path.toString().replace(path.getFileSystem().getSeparator(), "/"));
		}
		out.endArray();
	}

	private static Set<Path> readPaths(JsonReader in) throws IOException {
		Set<Path> paths = new HashSet<>();
		in.beginArray();
		while (in.hasNext()) {
			String text = in.nextString();
			try {
				if (text.contains("\\")) {
					throw new IllegalArgumentException("JSON paths must use '/'");
				}
				if (!paths.add(ChrysalisModification.normalizePath(Path.of(text)))) {
					throw new IllegalArgumentException("Duplicate ignore path");
				}
			} catch (IllegalArgumentException e) {
				throw new JsonParseException("Invalid ignore path: " + text, e);
			}
		}
		in.endArray();
		return paths;
	}

	private static void requireUnique(Set<String> fields, String field) {
		if (!fields.add(field)) {
			throw new JsonParseException("Duplicate field: " + field);
		}
	}

	private record RawChange(Operation operation, String expected, String replacement) {}
}
