package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.steam.IVaultable;

class WeaverStorageLogTests {
	@TempDir Path temp;
	private static final IVaultable ARTIFACT = ChrysalisNavigationTests.ARTIFACT;
	private final List<String> messages = new CopyOnWriteArrayList<>();
	private Logger logger;
	private AbstractAppender appender;

	@BeforeEach void captureLogs() {
		logger = (Logger) LogManager.getLogger(Weaver.class);
		appender = new AbstractAppender("weaver-storage-test", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
		};
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach void releaseLogs() {
		logger.removeAppender(appender);
		appender.stop();
	}

	private Path source() throws Exception {
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("same-a"), "shared");
		Files.writeString(source.resolve("same-b"), "shared");
		Files.writeString(source.resolve("unchanged"), "old-b");
		Files.writeString(source.resolve("one.jar"), "jar-one");
		Files.writeString(source.resolve("two.jar"), "jar-two");
		return source;
	}
	private void expect(String message) { assertTrue(messages.contains(message), () -> "Missing: " + message + "\n" + messages); }
	private long blobs(Vault vault) throws Exception { try (var files = Files.list(vault.blobDir)) { return files.count(); } }

	@Test void twoEditsReportOnlyTwoStoredBlobsAndTwoReplacements() throws Exception {
		Vault vault = new Vault(temp.resolve("vault")); Path source = source();
		new Weaver(vault, ARTIFACT, source).weave();
		expect("Context 'blobs': scanned 5 files (4 unique blobs, 1 duplicate paths)");
		expect("Context 'blobs': 4 blobs newly stored, 0 reused, 0 overwritten");
		assertEquals(4, blobs(vault));
		Files.writeString(source.resolve("one.jar"), "edited-one");
		Files.writeString(source.resolve("two.jar"), "edited-two");
		messages.clear();
		var child = new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").weave();
		expect("Context 'modded': scanned 5 files (4 unique blobs, 1 duplicate paths)");
		expect("Context 'modded': 2 blobs newly stored, 2 reused, 0 overwritten");
		expect("Modification 'modded': 2 file operations (0 additions, 2 replacements, 0 removals)");
		assertEquals(6, blobs(vault));
		assertEquals(2, child.getContext().orElseThrow().modification().changes().size());
		messages.clear();
		new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").force(true).weave();
		expect("Context 'modded': 0 blobs newly stored, 4 reused, 0 overwritten");
		expect("Modification 'modded': 2 file operations (0 additions, 2 replacements, 0 removals)");
		assertEquals(6, blobs(vault));
	}

	@Test void forcedSnapshotSeparatesOverwritesFromNewWrites() throws Exception {
		Vault vault = new Vault(temp.resolve("vault")); Path source = source();
		new Weaver(vault, ARTIFACT, source).weave();
		Files.writeString(source.resolve("new"), "new-content");
		messages.clear();
		new Weaver(vault, ARTIFACT, source, true).weave();
		expect("Context 'blobs': scanned 6 files (5 unique blobs, 1 duplicate paths)");
		expect("Context 'blobs': 1 blobs newly stored, 0 reused, 4 overwritten");
		assertEquals(5, blobs(vault));
		assertFalse(messages.stream().anyMatch(m -> m.startsWith("Modification 'blobs'")));
	}

	@Test void multiplePathsCanBeMultipleOperationsButOneNewBlob() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("removed"), "old");
		new Weaver(vault, ARTIFACT, source).weave();
		Files.delete(source.resolve("removed"));
		Files.writeString(source.resolve("added-a"), "new");
		Files.writeString(source.resolve("added-b"), "new");
		messages.clear();
		new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").weave();
		expect("Context 'modded': scanned 2 files (1 unique blobs, 1 duplicate paths)");
		expect("Context 'modded': 1 blobs newly stored, 0 reused, 0 overwritten");
		expect("Modification 'modded': 3 file operations (2 additions, 0 replacements, 1 removals)");
		assertEquals(2, blobs(vault));
	}

	@Test void emptyDesiredDirectoryReportsRemovalsWithoutBlobWrites() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("removed"), "old");
		new Weaver(vault, ARTIFACT, source).weave();
		Files.delete(source.resolve("removed"));
		messages.clear();
		new Weaver(vault, ARTIFACT, source).name("empty").parent("blobs").weave();
		expect("Context 'empty': scanned 0 files (0 unique blobs, 0 duplicate paths)");
		expect("Context 'empty': 0 blobs newly stored, 0 reused, 0 overwritten");
		expect("Modification 'empty': 1 file operations (0 additions, 0 replacements, 1 removals)");
		assertEquals(1, blobs(vault));
	}

	@Test void failedWeaveDoesNotEmitASuccessSummary() throws Exception {
		Vault vault = new Vault(temp.resolve("vault"));
		Path source = Files.createDirectory(temp.resolve("source"));
		Files.writeString(source.resolve("file"), "old");
		var base = new Weaver(vault, ARTIFACT, source).weave();
		Files.writeString(base.getBlobFile(base.getChrysalis().blobs().keySet().iterator().next()), "corrupt");
		messages.clear();
		assertThrows(IntegrityException.class, () -> new Weaver(vault, ARTIFACT, source).name("modded").parent("blobs").weave());
		assertFalse(vault.hasChrysalis(ARTIFACT, "modded"));
		assertTrue(messages.isEmpty());
	}
}
