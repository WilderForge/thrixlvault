package com.wildermods.thrixlvault.programs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import com.wildermods.thrixlvault.ChrysalisizedVault;
import com.wildermods.thrixlvault.MassDownloadWeaver;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.exception.MissingVersionException;
import com.wildermods.thrixlvault.steam.IDownloadable;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class DownloadFirstMissing {

	public static void main(String[] args) throws Throwable {
		
		WildermythManifest.init();
		Collection<IDownloadable> manifests = WildermythManifest.manifestStream()
			.filter(WildermythManifest::isPublic)
			.filter(manifest -> {return manifest.version().equals("0.6+38");})
			.collect(Collectors.toList());
		
		ArrayList<IDownloadable> toDownload = new ArrayList<IDownloadable>();
		for(IDownloadable manifest : manifests) {
			try {
				Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
				Path chrysalisFile = vault.getChrysalisFile(manifest);
				ChrysalisizedVault cVault = vault.chrysalisize(manifest);
				cVault.verifyBlobs();
			}
			catch(Throwable t) {
				t.printStackTrace();
				toDownload.add(manifest);
			}
		}
		
		if(toDownload.size() == 0) {
			manifests = toDownload;
		}
		else {
			manifests = Set.of(toDownload.iterator().next());
		}
		
		MassDownloadWeaver downloader = new MassDownloadWeaver("wilderforge", manifests);
		downloader.run();
		
		for(IDownloadable manifest : manifests) {
			Vault vault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
			String header = "========Verifying " + manifest.name() + "========";
			System.out.println(header);
			Path chrysalisFile = vault.getChrysalisFile(manifest);
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
