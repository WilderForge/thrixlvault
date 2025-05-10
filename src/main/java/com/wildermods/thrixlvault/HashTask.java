package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Path;

import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;

@FunctionalInterface
public interface HashTask {
	void call(Hash hash, Path vaultPath, Chrysalis chrysalis) throws IOException, IntegrityException;
}
