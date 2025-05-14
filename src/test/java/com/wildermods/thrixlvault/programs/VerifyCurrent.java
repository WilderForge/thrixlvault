package com.wildermods.thrixlvault.programs;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.ChrysalisizedVault;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.steam.IDownloadable;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class VerifyCurrent {

	private static final Logger LOGGER = LogManager.getLogger();
	
	public static void main(String[] args) throws IntegrityException, IOException {
		Collection<IDownloadable> manifests = WildermythManifest.manifestStream()
			.filter(WildermythManifest::isPublic)
			.collect(Collectors.toList());
		
		for(IDownloadable manifest : manifests) {
			try {
				Vault currentVault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
				
				LOGGER.info("=====================" + manifest + "=====================");
				ChrysalisizedVault currentManifest = currentVault.chrysalisize(manifest);
				currentManifest.verifyBlobs();
				

			}
			catch(Throwable t) {
				System.err.println("Error processing manifest " + manifest);
				t.printStackTrace();
			}
		}
		
	}

}

