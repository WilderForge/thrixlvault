package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.nio.file.Path;

import com.google.gson.JsonObject;

public abstract class WildermythModFile implements WildermythMod {

	protected final Path path;
	
	public WildermythModFile(Path path) {
		this.path = path;
	}
	
	public final Path getModFile(){
		return path;
	}
	
	public abstract JsonObject getModJson() throws IOException;
	
	@Override
	public final Path artifactPath() {
		return path;
	}
	
	@Override
	public String toString() {
		return getClass().getSimpleName() + " " + modid();
	}
	
}
