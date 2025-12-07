package com.wildermods.thrixlvault.programs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.ChrysalisizedVault;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.exception.VersionParsingException;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class ExportLatest {

	private static final Logger LOGGER = LogManager.getLogger();
	
	public static void main(String[] args) throws IntegrityException, IOException, InterruptedException, ExecutionException, VersionParsingException {
		
		WildermythManifest latest = WildermythManifest.get(OS.getOS(), "1.16+559");
		ChrysalisizedVault latestChrysalis = Vault.DEFAULT.chrysalisize(latest);
		
		LOGGER.info("Exporting " + latest);
		latestChrysalis.export(Path.of(System.getProperty("user.home")).resolve("LATEST_EXPORT_TEST"), false);
	}
	
}
