package com.wildermods.thrixlvault.programs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

import com.wildermods.thrixlvault.ChrysalisizedVault;
import com.wildermods.thrixlvault.MassDownloadWeaver;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.steam.IDownloadable;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class DownloadAllMissing {

	public static void main(String[] args) throws Throwable {
		
		Collection<IDownloadable> manifests = WildermythManifest.manifestStream()
			.filter(WildermythManifest::isPublic)
			.collect(Collectors.toList());
		
		ArrayList<IDownloadable> toDownload = new ArrayList<IDownloadable>();
		for(IDownloadable manifest : manifests) {
			try {
				Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
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
		
		for(IDownloadable manifest : manifests) {
			Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
			String header = "========Verifying " + manifest.name() + "========";
			System.out.println(header);
			ChrysalisizedVault cVault = vault.chrysalisize(manifest);
			try {
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
	
	private static WildermythManifest get(OS os, String version) throws MissingVersionException {
		return WildermythManifest.get(os, version);
	}
}
