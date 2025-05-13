package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import com.wildermods.masshash.Blob;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.Hasher;
import com.wildermods.masshash.utils.Reference;

public class Chrysalis extends Hasher implements Cloneable {
	
	static final Logger LOGGER = LogManager.getLogger();
	
	Chrysalis(){
		//NO-OP
	};
	
	Chrysalis(Path dir, BiConsumer<Reference<Path>, Blob> forEachBlob) throws IOException {
		super(Files.walk(dir), forEachBlob);
	}

	public static Chrysalis fromDir(Path path) throws IOException {
		return fromDir(path, (p, blob)->{});
	}
	
	public static Chrysalis fromDir(Path path, BiConsumer<Reference<Path>, Blob> forEachBlob) throws IOException {
		LOGGER.info("Constructing Chrysalis from " + path);
		Chrysalis ret = new Chrysalis(path, forEachBlob);

		return ret;
	}
	
	public static Chrysalis fromFile(Path path) throws JsonSyntaxException, JsonIOException, IOException {
		return Weaver.GSON.fromJson(Files.newBufferedReader(path), Chrysalis.class);
	}
	
	@Override
	public boolean equals(Object o) {
		if(o instanceof Chrysalis) {
			return this.blobs.equals(((Chrysalis)o).blobs);
		}
		return false;
	}
	
	@Override
	public Chrysalis clone() {
		Chrysalis chrysalis = new Chrysalis();
		
		chrysalis.blobs = TreeMultimap.create(
			Comparator.comparing(Hash::hash),
			Ordering.natural()
		);
		
		for(Hash key : this.blobs.keySet()) {
			for(Path path : this.blobs.values()) {
				chrysalis.blobs.put(key, path);
			}
		}
		
		chrysalis.blobs = Multimaps.synchronizedSetMultimap(chrysalis.blobs);
		
		return chrysalis;
	}
	
	public SetMultimap<Hash, Path> blobs() {
		return blobs;
	}

	public void setBlobs(SetMultimap<Hash, Path> blobs) {
		this.blobs = blobs;
	}
	
}
