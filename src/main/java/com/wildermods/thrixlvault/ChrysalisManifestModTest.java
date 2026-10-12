package com.wildermods.thrixlvault;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.io.file.PathUtils;

import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class ChrysalisManifestModTest {

    public static void main(String[] args) throws Exception {
        if (OS.getOS() != OS.LINUX) {
            throw new IllegalStateException("This test is Linux-only");
        }
        
        

        Vault vault = Vault.DEFAULT;
        var version = WildermythManifest.get(OS.LINUX, "1.16+562");
        var base = vault.chrysalisize(version);

        Path destination = Path.of(
            System.getProperty("user.home"),
            "Desktop",
            "CHRYSALIS-MANIFEST-MODTEST"
        );

        if (Files.exists(destination)) {
            throw new FileAlreadyExistsException(destination.toString());
        }

        Path temporary = Files.createTempDirectory(
            "chrysalis-manifest-modtest-"
        );

        
        System.out.println("MassHash loaded from: " +
        	    com.wildermods.masshash.BlobFactory.class
        	        .getProtectionDomain().getCodeSource().getLocation());

        	System.out.println("ByteUtil loaded from: " +
        	    com.wildermods.masshash.utils.ByteUtil.class
        	        .getProtectionDomain().getCodeSource().getLocation());

        	var factory = base.getChrysalis().getBlobFactory();
        	System.out.println("Digest instance reused: " +
        	    (factory.digest.get() == factory.digest.get()));
        
        try {
            // 1. Export the original game.
            base.export(temporary, true);

            // 2. Find only JARs directly in the game's root directory.
            List<Path> jars;
            try (var files = Files.list(temporary)) {
                jars = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList();
            }

            int modified = 0;
            for (Path jar : jars) {
                try (var zip = FileSystems.newFileSystem(jar, Map.of())) {
                    if (Files.deleteIfExists(
                            zip.getPath("/META-INF/MANIFEST.MF"))) {
                        modified++;
                    }
                }
            }

            // 3. Store the edited bytes and save modded.json beside blobs.json.
            var modded = new Weaver(vault, version, temporary)
                .name("modded")
                .parent("blobs")
                .weave();

            // 4. Export the resolved modded game.
            Files.createDirectories(destination);
            modded.export(destination, true);

            System.out.println("Modified JARs: " + modified);
            System.out.println("Exported to: " + destination);
        } finally {
            PathUtils.deleteDirectory(temporary);
        }
    }
}