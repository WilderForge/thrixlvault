package com.wildermods.thrixlvault;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface IResource {

	@Nullable
	public Path getResourceLocation();
	
}
