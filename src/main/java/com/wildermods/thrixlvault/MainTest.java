package com.wildermods.thrixlvault;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class MainTest {

	public static void main(String[] args) throws IOException, InterruptedException {
		Set<WildermythManifest> manifests = WildermythManifest.manifestStream().filter(WildermythManifest::isPublic).collect(Collectors.toSet());
		
		for(WildermythManifest manifest : manifests) {
			Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
			
			ChrysalisizedVault c = vault.chrysalisize(manifest);
			c.verifyBlobs();
		}
	}
	
}
