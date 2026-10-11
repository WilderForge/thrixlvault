package com.wildermods.thrixlvault;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.utils.ByteUtil;

/** Streaming hash checks with an independent digest for each concurrent caller. */
final class BlobIO {
	private BlobIO() {}

	static Hash hash(BlobFactory factory, Path file) throws IOException {
		try {
			BlobFactory local = factory.provider == null ? new BlobFactory(factory.algorithm())
				: new BlobFactory(factory.provider, factory.algorithm());
			try (var input = Files.newInputStream(file)) {
				return Hash.of(local.digest, ByteUtil.hash(input, local.digest));
			}
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Unsupported hash algorithm: " + factory.algorithm(), e);
		}
	}

	static void verify(BlobFactory factory, Path file, Hash expected) throws IOException, IntegrityException {
		Hash actual = hash(factory, file);
		if (!expected.hashEquals(actual)) {
			throw new IntegrityException("Expected hash " + expected.hash() + " but got " + actual.hash());
		}
	}
}
