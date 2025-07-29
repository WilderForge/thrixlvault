package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.wildermods.thrixlvault.steam.IVersioned;

public class Wildermyth implements WildermythMod {

	private final String version;
	
	public Wildermyth(IVersioned version) {
		this.version = version.version();
	}
	
	@Override
	public String name() {
		return "Wildermyth";
	}

	@Override
	public String version() {
		return version;
	}

	@Override
	public String modid() {
		return "wildermyth";
	}

	@Override
	public Path getModFile() {
		return null;
	}

	@Override
	public JsonObject getModJson() throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Path artifactPath() {
		throw new UnsupportedOperationException();
	}

}
