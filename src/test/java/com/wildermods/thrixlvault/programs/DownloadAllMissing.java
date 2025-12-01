package com.wildermods.thrixlvault.programs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

import com.wildermods.thrixlvault.ChrysalisizedVault;
import com.wildermods.thrixlvault.MassDownloadWeaver;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.exception.UnknownVersionException;
import com.wildermods.thrixlvault.steam.ISteamDownloadable;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class DownloadAllMissing {

	public static void main(String[] args) throws Throwable {
		
		
		Collection<ISteamDownloadable> manifests = WildermythManifest.manifestStream().filter((manifest) -> {
				return manifest.isPublic() || (manifest.isVersionKnown() && manifest.isLatest());
		})
		.collect(Collectors.toList());
		
		ArrayList<ISteamDownloadable> toDownload = new ArrayList<>();
		for(ISteamDownloadable manifest : manifests) {
			try {
				Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR);
				ChrysalisizedVault cVault = vault.chrysalisize(manifest);
				cVault.verifyBlobs();
			}
			catch(Throwable t) {
				t.printStackTrace();
				toDownload.add(manifest);
			}
		}
		
		manifests = toDownload;
		
		MassDownloadWeaver downloader = new MassDownloadWeaver("wilderforge", manifests);
		downloader.run();
		
		for(ISteamDownloadable manifest : manifests) {
			Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR);
			String header = "========Verifying " + manifest.name() + "========";
			System.out.println(header);
			try {
				ChrysalisizedVault cVault = vault.chrysalisize(manifest);
				cVault.verifyBlobs();
			}
			catch(Throwable t) {
				t.printStackTrace();
			}
			for(int i = 0; i < header.length(); i++) {
				System.out.print("=");
			}
			System.out.println();
		}
		
	}
	
	private static WildermythManifest get(OS os, String version) throws UnknownVersionException {
		return WildermythManifest.get(os, version);
	}
}
