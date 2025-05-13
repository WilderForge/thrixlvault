package com.wildermods.thrixlvault.utils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.commons.io.file.PathUtils;

public class FileUtil {

	public static void deleteDirectory(Path path) throws IOException {
		if (!Files.exists(path)) {
			throw new FileNotFoundException(path.toString());
		}

		if (!Files.isDirectory(path)) {
			throw new NotDirectoryException(path.toString());
		}

		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) throw exc;
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
	
	public static void copyDirectory(Path source, Path dest) throws IOException {
		PathUtils.copyDirectory(source, dest);
		if(PathUtils.directoryContentEquals(source, dest)) {
			deleteDirectory(source);
		}
		else {
			throw new IOException("copied directory " + source + " to " + dest + " but content not equal");
		}
	}
	
	
	
}
