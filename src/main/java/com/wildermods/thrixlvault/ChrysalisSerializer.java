package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import java.util.Set;

import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.wildermods.masshash.Blob;
import com.wildermods.masshash.Hash;

public class ChrysalisSerializer extends TypeAdapter<Chrysalis>{
	
	private static final int SCHEMA = 1;
	
	@Override
	public void write(JsonWriter out, Chrysalis value) throws IOException {
		out.beginObject();
		{
			out.name("schema");
			out.value(SCHEMA);
			
			out.name("blobs");
			out.beginObject();
			{
				for(Hash hash : value.blobs().keySet()) {
					out.name(hash.hash());
					out.beginArray();
					for(Path path : value.blobs().get(hash)) {
						out.value(path.toString());
					}
					out.endArray();
				}
			}
			out.endObject();
		}
		out.endObject();
	}
	
	@Override
	@SuppressWarnings("deprecation")
	public Chrysalis read(JsonReader in) throws IOException {
		Chrysalis ret = new Chrysalis();
		
		in.beginObject();
		
		int schema = 0;
		{
			String name = in.nextName();
			if(name.equals("schema")) {
				schema = in.nextInt();
			}
			else {
				return parseSchema_0(ret, in, new Blob((byte[])null, name));
			}
		}
		
		in.nextName();
		in.beginObject();
		{
			Map<Hash, Set<Path>> temp = new HashMap<>();
			
			while(in.hasNext()) {
				Hash hash = new Blob((byte[])null, in.nextName());
				in.beginArray();
				Set<Path> paths = temp.computeIfAbsent(hash, set -> new HashSet<>());
				while(in.hasNext()) {
					paths.add(Path.of(in.nextString()));
				}
				in.endArray();
			}
			in.endObject();
			
			// Sort and build the final TreeMultimap
			TreeMultimap<Hash, Path> sorted = TreeMultimap.create(
				Comparator.comparing(Hash::hash),
				Ordering.natural()
			);
			for (Map.Entry<Hash, Set<Path>> entry : temp.entrySet()) {
				sorted.putAll(entry.getKey(), entry.getValue());
			}
			ret.setBlobs(Multimaps.synchronizedSetMultimap(sorted));
		}
		in.endObject();
		
		return ret;
	}

	
	@SuppressWarnings("deprecation")
	static Chrysalis parseSchema_0(Chrysalis ret, JsonReader reader, Hash firstHash) throws IOException {
		Map<Hash, Set<Path>> temp = new HashMap<>();
		{
			reader.beginArray();
			Set<Path> firstPaths = temp.computeIfAbsent(firstHash, set -> new HashSet<>());
			while(reader.hasNext()) {
				firstPaths.add(Path.of(reader.nextString()));
			}
			reader.endArray();
		}
		
		while(reader.hasNext()) {
			Hash hash = new Blob((byte[])null, reader.nextName());
			Set<Path> paths = temp.computeIfAbsent(hash, set -> new HashSet<>());
			reader.beginArray();
			{
				while(reader.hasNext()) {
					paths.add(Path.of(reader.nextString()));
				}
			}
			reader.endArray();
		}
		reader.endObject();
		
		TreeMultimap<Hash, Path> sorted = TreeMultimap.create(
			Comparator.comparing(Hash::hash),
			Ordering.natural()
		);
		for (Map.Entry<Hash, Set<Path>> entry : temp.entrySet()) {
			sorted.putAll(entry.getKey(), entry.getValue());
		}
		ret.setBlobs(Multimaps.synchronizedSetMultimap(sorted));
		
		return ret;
	}
	
	//This is a slower but more readable implementation of the read() method.
	//If the schema ever gets too complex to parse with raw readers, we can
	//fall back to using this implementation.
	
	/*
	 
	import static com.wildermods.thrixlvault.ChrysalisSerializer.Updater.*;
	import com.google.gson.JsonArray;
	import com.google.gson.JsonElement;
	import com.google.gson.JsonObject;
	import com.google.gson.JsonParser;
	import com.google.gson.JsonPrimitive;
	import java.util.Map.Entry;

	@Override
	public Chrysalis read2(JsonReader in) throws IOException {
		Chrysalis ret = new Chrysalis();
		
		JsonObject json;
		{
			JsonObject data = (JsonObject) JsonParser.parseReader(in);
			json = update(data); //asume this updated schema 0 to schema 1
		}
		
		JsonObject blobs = json.getAsJsonObject("blobs");
		
		Map<Hash, Set<Path>> temp = new HashMap<>();
		for(Entry<String, JsonElement> e: blobs.entrySet()) {
			Hash hash = new Blob((byte[])null, e.getKey());
			Set<Path> paths = temp.computeIfAbsent(hash, set -> new HashSet<>());
			JsonArray pathArray = e.getValue().getAsJsonArray();
			for(JsonElement pathElement : pathArray) {
				paths.add(Path.of(pathElement.getAsString()));
			}
		}
		
		// Sort and build the final TreeMultimap
		TreeMultimap<Hash, Path> sorted = TreeMultimap.create(
			Comparator.comparing(Hash::hash),
			Ordering.natural()
		);
		for (Map.Entry<Hash, Set<Path>> entry : temp.entrySet()) {
			sorted.putAll(entry.getKey(), entry.getValue());
		}
		ret.blobs = Multimaps.synchronizedSetMultimap(sorted);
		
		return ret;
	}
	
	public JsonObject update(JsonObject json) {
		int schema = 0;
		JsonPrimitive schemaElement = json.getAsJsonPrimitive("schema");
		if(schemaElement != null) {
			schema = schemaElement.getAsInt();
		}
		if(schema > SCHEMA) {
			throw new UnsupportedOperationException("Future schema version detected. Schema " + schema + " found, but we are on " + SCHEMA);
		}
		
		
		if(schema == 0) {
			json = update_0_to_1(json);
		}
		
		
		
		int newSchema = json.get("schema").getAsInt();
		if(newSchema < SCHEMA) {
			throw new AssertionError();
		}
		
		return json;
	}
	
	
	static class Updater {
	
		static JsonObject update_0_to_1(JsonObject original) {
			JsonObject ret = new JsonObject();
			ret.addProperty("schema", 1);
			JsonObject blobs = new JsonObject();
			Path installDir = Path.of(System.getProperty("user.home")).resolve("thrixlvault").resolve("current_download");
			for(Entry<String, JsonElement> e: original.entrySet()) {
				JsonArray badArray = e.getValue().getAsJsonArray();
				JsonArray fixedArray = new JsonArray();
				for(JsonElement badPath : badArray) {
					Path fixedPath = installDir.relativize(Path.of(badPath.getAsString()));
					fixedArray.add(fixedPath.toString());
				}
				
				blobs.add(e.getKey(), fixedArray);
			}
			ret.add("blobs", blobs);
			return ret;
		}
	}
	*/
}
