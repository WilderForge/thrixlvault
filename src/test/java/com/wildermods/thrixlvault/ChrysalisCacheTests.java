package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.wildermods.masshash.BlobFactory;
import com.wildermods.masshash.Hash;

@SuppressWarnings("deprecation")
class ChrysalisCacheTests {
	@TempDir Path temp;
	private final BlobFactory factory = new BlobFactory();
	private final Hash hash = factory.blob(new byte[] {1, 2, 3});

	@Test
	void noIgnoresReuseStoredImmutableDataAndConstructionCopiesInputs() {
		SetMultimap<Hash, Path> input = HashMultimap.create();
		input.put(hash, Path.of("file"));
		Chrysalis chrysalis = new Chrysalis();
		chrysalis.setBlobs(input);
		var active = chrysalis.activeBlobs();
		assertSame(chrysalis.blobs(), active);
		assertSame(active, chrysalis.activeBlobs());
		input.clear();
		assertEquals(1, active.size());
		assertThrows(UnsupportedOperationException.class, () -> active.clear());
		assertThrows(UnsupportedOperationException.class, () -> chrysalis.blobs().get(hash).clear());
		assertThrows(UnsupportedOperationException.class, () -> chrysalis.results().clear());
	}

	@Test
	void concurrentFirstCallsPublishOneFilteredMap() throws Exception {
		Chrysalis chrysalis = snapshot();
		chrysalis.setIgnoredPaths(Set.of(Path.of("hidden")));
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(8);
		try {
			var futures = new ArrayList<Future<SetMultimap<Hash, Path>>>();
			for (int i = 0; i < 64; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return chrysalis.activeBlobs();
				}));
			}
			start.countDown();
			var active = futures.getFirst().get();
			for (var future : futures) {
				assertSame(active, future.get());
			}
			assertEquals(Set.of(Path.of("visible")), active.get(hash));
			assertSame(active, chrysalis.activeBlobs());
			assertThrows(UnsupportedOperationException.class, () -> active.put(hash, Path.of("other")));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void internalSettersInvalidateWithoutChangingPreviouslyReturnedMaps() {
		Chrysalis chrysalis = snapshot();
		var original = chrysalis.activeBlobs();
		chrysalis.setIgnoredPaths(Set.of(Path.of("hidden")));
		var filtered = chrysalis.activeBlobs();
		assertNotSame(original, filtered);
		assertEquals(2, original.size());
		assertEquals(1, filtered.size());
		SetMultimap<Hash, Path> replacement = HashMultimap.create();
		replacement.put(hash, Path.of("other"));
		chrysalis.setBlobs(replacement);
		var newActive = chrysalis.activeBlobs();
		assertNotSame(filtered, newActive);
		assertEquals(Set.of(Path.of("other")), newActive.get(hash));
		assertEquals(Set.of(Path.of("visible")), filtered.get(hash));
		chrysalis.setIgnoredPaths(Set.of());
		assertSame(chrysalis.blobs(), chrysalis.activeBlobs());
	}

	@Test
	void clonesShareCachedDataButSetterChangesRemainIndependent() {
		Chrysalis original = snapshot();
		original.setIgnoredPaths(Set.of(Path.of("hidden")));
		var cached = original.activeBlobs();
		Chrysalis clone = original.clone();
		assertSame(original.blobs(), clone.blobs());
		assertSame(cached, clone.activeBlobs());
		clone.setIgnoredPaths(Set.of(Path.of("visible")));
		assertEquals(Set.of(Path.of("hidden", "file")), clone.activeBlobs().get(hash));
		assertSame(cached, original.activeBlobs());
		assertEquals(Set.of(Path.of("visible")), original.activeBlobs().get(hash));
		assertSame(cached, original.checkingSnapshot().activeBlobs());
	}

	@Test
	void hashingAndDeserializationProduceImmutableSnapshots() throws Exception {
		Files.write(temp.resolve("file"), new byte[] {1, 2, 3});
		Chrysalis hashed = Chrysalis.fromDir(temp, (path, blob) -> path.set(temp.relativize(path.get())));
		assertSame(hashed.blobs(), hashed.activeBlobs());
		assertThrows(UnsupportedOperationException.class, () -> hashed.blobs().clear());
		Chrysalis parsed = Weaver.GSON.fromJson(Weaver.GSON.toJson(hashed), Chrysalis.class);
		assertSame(parsed.blobs(), parsed.activeBlobs());
		assertThrows(UnsupportedOperationException.class, () -> parsed.blobs().clear());
	}

	private Chrysalis snapshot() {
		SetMultimap<Hash, Path> blobs = HashMultimap.create();
		blobs.put(hash, Path.of("visible"));
		blobs.put(hash, Path.of("hidden", "file"));
		Chrysalis chrysalis = new Chrysalis();
		chrysalis.setBlobs(blobs);
		return chrysalis;
	}
}
