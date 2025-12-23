package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Files;
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
import com.wildermods.thrixlvault.steam.SkippedDownload;
import com.wildermods.thrixlvault.utils.FileUtil;

public class MassDownloadWeaver extends Downloader<ISteamDownloadable, ISteamDownload>{
	
	final String username;
	final int totalDownloads;
	final HashMap<ISteamDownloadable, Integer> failedDownloads = new HashMap<>();
	final HashMap<ISteamDownloadable, Integer> skippedDownloads = new HashMap<>();
	volatile boolean stopsOnInterrupt = false;
	
	public MassDownloadWeaver(String username, Collection<ISteamDownloadable> downloadables) throws IOException, InterruptedException {
		super(downloadables);
		this.username = username;
		totalDownloads = downloadables.size();
	}
	
	public MassDownloadWeaver setStopOnInterrupt(boolean shouldStop) {
		this.stopsOnInterrupt = shouldStop;
		return this;
	}
	
	public boolean stopsOnInterrupt() {
		return stopsOnInterrupt;
	}
	
	public Set<ISteamDownload> runImpl() throws IOException {
		final HashSet<ISteamDownloadable> downloadables = new LinkedHashSet<>(this.downloadables);
		final HashSet<ISteamDownload> finishedDownloads = new HashSet<ISteamDownload>();
		while(!downloadables.isEmpty()) {
			SteamDownloader downloader = new SteamDownloader(username, downloadables);
			try {

				
				downloader.setConsumer((download) -> {
					if(download instanceof CompletedDownload) {
						try {
							Weaver weaver = new Weaver(Vault.DEFAULT, download);
						} catch (IOException | IntegrityException e) {
							throw new RuntimeException(e);
						}
					}
					try {
						if(Files.exists(download.dest())) {
							FileUtil.deleteDirectory(download.dest());
						}
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
					else if (download instanceof CompletedDownload){
						System.out.println("Successfully downloaded " + download);
					}
					downloadables.remove(download);
					finishedDownloads.add(download);
				}
			}
			catch(InterruptedException e) {
				if(stopsOnInterrupt) {
					for(ISteamDownloadable download : downloadables) {
						FailedDownload fail = new FailedDownload(download, downloader.getInstallDir(), e);
						finishedDownloads.add(fail);
					}
					downloadables.clear();
					break;
				}
			}
		}
		
		int failed = 0;
		int success = 0;
		int skipped = 0;
		int other = 0;
		final int remaining = downloadables.size();
		for(ISteamDownload download : finishedDownloads) {
			if(download instanceof CompletedDownload) {
				success++;
			}
			else if(download instanceof FailedDownload) {
				failed++;
			}
			else if (download instanceof SkippedDownload) {
				skipped++;
			}
			else {
				other++;
			}
		}
		System.out.println("Total downloads scheduled: " + totalDownloads);
		System.out.println("Successful downloads:" + success + "/" + totalDownloads);
		System.out.println("Skipped downloads:" + skipped + "/" + totalDownloads);
		System.out.println("Failed downloads:" + failed + "/" + totalDownloads);
		System.out.println("Remaining downloads:" + remaining + "/" + totalDownloads);
		System.out.println("Other status downloads: " + other + "/" + totalDownloads);
		System.out.println("Downloads unaccounted for: " + (totalDownloads - (failed + success + skipped + other + remaining)));
		return finishedDownloads;
	}

	@Override
	protected ImmutableSet<ISteamDownload> getDownloadsInProgress() throws UnsupportedOperationException {
		throw new UnsupportedOperationException();
	}
	
}
