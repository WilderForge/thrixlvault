package com.wildermods.thrixlvault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import com.google.common.collect.SetMultimap;
import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.exception.DatabaseError;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class FindAllManifestedJars {

	public static void main(String[] args) throws IOException {
		Set<WildermythManifest> manifests = WildermythManifest.manifestStream()
			.filter(
				(m) -> m.isPublic() && m.isVersionKnown()
			)
			.collect(Collectors.toSet());
		
		Path tempDir = Files.createTempDirectory("thrixlvault-jars");
		System.out.println("Temp dir: " + tempDir);
		Vault tempVault = new Vault(tempDir.resolve("vault"));
		for(WildermythManifest manifest : manifests) {
			System.out.println("Processing " + manifest.gameName() + " " + manifest.os() + " " + manifest.version());
			
			try {
				ChrysalisizedVault cv = Vault.DEFAULT.chrysalisize(manifest);
				Chrysalis chrysalis = cv.getChrysalis();
				SetMultimap<Hash, Path> files = chrysalis.results();
				
				boolean changed = false;
				Path newSourcePath = tempDir.resolve(manifest.gameName()).resolve(manifest.os().toString()).resolve(manifest.version());
				for (Entry<Hash, Path> entry : files.entries()) {
					Path path = entry.getValue();
					if(path.startsWith("jre")) {
						continue; //we don't want to fuck up the classloading of the jre itself
					}

					if (!path.toString().endsWith(".jar")) {
						continue;
					}

					Path tempJar = newSourcePath.resolve(path);
					Files.createDirectories(tempJar.getParent());
					Path original = cv.getBlobFile(entry.getKey());

					try (FileSystem zip = FileSystems.newFileSystem(original, Map.of("readonly", "true"))) {
						Path jarManifestPath = zip.getPath("/META-INF/MANIFEST.MF");

						if (!Files.exists(jarManifestPath)) {
							continue;
						}

						Manifest jarManifest;

						try (InputStream in = Files.newInputStream(jarManifestPath)) {
							jarManifest = new Manifest(in);
						}

						if (jarManifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) == null) {
							continue;
						}

						jarManifest.getMainAttributes().remove(Attributes.Name.CLASS_PATH);
						Files.copy(original, tempJar);

						try (FileSystem tempZip = FileSystems.newFileSystem(tempJar)) {
							Path tempManifestPath = tempZip.getPath("/META-INF/MANIFEST.MF");

							try (OutputStream out = Files.newOutputStream(tempManifestPath)) {
								jarManifest.write(out);
								changed = true;
							}
						}
					}
				}
				if(changed) {
					Weaver weaver = new Weaver(tempVault, manifest, newSourcePath, "classpath_fix");
					break; //only do one
				}
			} catch (IOException | DatabaseError | IntegrityException e) {
				e.printStackTrace();
				break; //emergency stop
			}
		}
	}
	
}
