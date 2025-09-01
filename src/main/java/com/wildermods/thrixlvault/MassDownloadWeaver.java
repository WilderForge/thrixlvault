package com.wildermods.thrixlvault;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.steam.CompletedDownload;
import com.wildermods.thrixlvault.steam.FailedDownload;
import com.wildermods.thrixlvault.steam.ISteamDownload;
import com.wildermods.thrixlvault.steam.ISteamDownloadable;
import com.wildermods.thrixlvault.utils.FileUtil;

public class MassDownloadWeaver extends Downloader<ISteamDownloadable, ISteamDownload>{
	
	final String username;
	final int totalDownloads;
	final HashMap<ISteamDownloadable, Integer> failedDownloads = new HashMap<>();
	
	public MassDownloadWeaver(String username, Collection<ISteamDownloadable> downloadables) throws IOException, InterruptedException {
		super(downloadables);
		this.username = username;
		totalDownloads = downloadables.size();
	}
	
	public Set<ISteamDownload> runImpl() throws IOException {
		final HashSet<ISteamDownloadable> downloadables = new LinkedHashSet<>(this.downloadables);
		final HashSet<ISteamDownload> finishedDownloads = new HashSet<ISteamDownload>();
		while(!downloadables.isEmpty()) {
			
			SteamDownloader downloader = new SteamDownloader(username, downloadables);
			
			downloader.setConsumer((download) -> {
				if(download instanceof CompletedDownload) {
					try {
						Weaver weaver = new Weaver(Vault.DEFAULT, download);
					} catch (IOException | IntegrityException e) {
						throw new RuntimeException(e);
					}
				}
				try {
					FileUtil.deleteDirectory(download.dest());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			
			Set<ISteamDownload> downloads = downloader.run();
			for(ISteamDownload download : downloads) {
				if(download instanceof FailedDownload) {
					final int attempt;
					if(!failedDownloads.containsKey(download)) {
						attempt = 1;
						failedDownloads.put(download, attempt);
					}
					else {
						attempt = failedDownloads.get(download);
						failedDownloads.put(download, attempt + 1);
					}
					((FailedDownload) download).failReason().printStackTrace();
					System.out.println("Attempt " + attempt + " failed for manifest " + download);
					if(attempt >= 30) {
						System.out.println("Could not download " + download);
						downloadables.remove(download);
						finishedDownloads.add(download);
					}
				}
				else {
					System.out.println("Successfully downloaded " + download);
					downloadables.remove(download);
					finishedDownloads.add(download);
				}
			}
		}
		
		int failed = 0;
		int success = 0;
		int other = 0;
		final int remaining = downloadables.size();
		for(ISteamDownload download : finishedDownloads) {
			if(download instanceof CompletedDownload) {
				success++;
			}
			else if(download instanceof FailedDownload) {
				failed++;
			}
			else {
				other++;
			}
		}
		System.out.println("Total downloads scheduled: " + totalDownloads);
		System.out.println("Successful downloads:" + success + "/" + totalDownloads);
		System.out.println("Failed downloads:" + failed + "/" + totalDownloads);
		System.out.println("Remaining downloads:" + remaining + "/" + totalDownloads);
		System.out.println("Other status downloads: " + other + "/" + totalDownloads);
		System.out.println("Downloads unaccounted for: " + (totalDownloads - (failed + success + other + remaining)));
		return finishedDownloads;
	}

	@Override
	protected ImmutableSet<ISteamDownload> getDownloadsInProgress() throws UnsupportedOperationException {
		throw new UnsupportedOperationException();
	}
	
}
