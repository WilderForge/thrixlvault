package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.wildermods.thrixlvault.utils.OS;

public class Trimmer {

	public static void main(String args[]) throws IOException {
		Path vault = Weaver.OUTPUT_DIR;
		final String toReplace = "/home/gamebuster/thrixlvault/current_download/";
		final String replacement = "";
		for(OS os : OS.values()) {
			Path osDir = vault.resolve(os.name());
			Files.walk(osDir, 1).filter(Files::isDirectory).forEach((versionDir) -> {
				try {
					Path blobIndex = versionDir.resolve("blobs.json");
					String content = Files.readString(versionDir.resolve("blobs.json"));
					content = content.replaceAll(toReplace, replacement);
					Files.write(blobIndex, content.getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
				} catch (IOException e) {
					System.err.println("Could not trim " + versionDir);
					e.printStackTrace();
				}
			});
		}
	}
	
}
