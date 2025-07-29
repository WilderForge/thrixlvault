package com.wildermods.thrixlvault.wildermyth;

import java.io.IOException;
import java.nio.file.Path;

import com.google.gson.JsonObject;

public class MissingMod implements WildermythMod {

	@Override
	public String name() {
		return "Missing Mod";
	}

	@Override
	public String version() {
		return "0.0.0";
	}

	@Override
	public Path artifactPath() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String modid() {
		return "missing_mod";
	}

	@Override
	public Path getModFile() {
		throw new UnsupportedOperationException();
	}

	@Override
	public JsonObject getModJson() throws IOException {
		throw new UnsupportedOperationException();
	}

}
