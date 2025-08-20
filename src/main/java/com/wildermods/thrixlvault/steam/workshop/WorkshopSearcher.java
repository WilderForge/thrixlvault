package com.wildermods.thrixlvault.steam.workshop;

import java.nio.file.Paths;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamAPICall;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUGC;
import com.codedisaster.steamworks.SteamUGC.MatchingUGCType;
import com.codedisaster.steamworks.SteamUGC.UGCQueryType;
import com.codedisaster.steamworks.SteamUGCCallback;
import com.codedisaster.steamworks.SteamUGCDetails;
import com.codedisaster.steamworks.SteamUGCQuery;
import com.wildermods.wilderforge.launch.WilderForge;
import com.worldwalkergames.legacy.context.LegacyViewDependencies;
import com.worldwalkergames.legacy.integations.SteamManager;

public class WorkshopSearcher implements SteamUGCCallback {
	
	private final LegacyViewDependencies dependencies;
	private final SteamUGC steamUGC;
	
	public WorkshopSearcher() {
		this.dependencies = WilderForge.getViewDependencies();
		steamUGC = new SteamUGC(this);
	}
	
	public void search() {
		//SteamManager.setGameLauncherMode(true);
		SteamUGCQuery query = steamUGC.createQueryAllUGCRequest(UGCQueryType.RankedByTotalUniqueSubscriptions, MatchingUGCType.Items, 763890, 763890, 1);
		SteamAPICall call = steamUGC.sendQueryUGCRequest(query);
		System.out.println("Query sent, call handle: " + call);
		//SteamManager.setGameLauncherMode(false);
	}
	
	@Override
	public void onUGCQueryCompleted(SteamUGCQuery query, int numResultsReturned, int totalMatchingResults, boolean isCachedData, SteamResult result) {
		if(result == SteamResult.OK) {
			
			SteamUGCDetails details = new SteamUGCDetails();
			
			System.out.println("Wow, there were results, " + totalMatchingResults + " were found!! (showing " + numResultsReturned + ")");
			for(int i = 0; i < numResultsReturned; i++) {
				if (steamUGC.getQueryUGCResult(query, i, details)) {
					long publishedFileID = Long.parseLong(details.getPublishedFileID().toString(), 16);
					String title = details.getTitle();
					System.out.println("Workshop ID: " + publishedFileID + " | TITLE: " + title);
				}
			}
			
		}
		else {
			System.out.println("Not okay");
		}
	}
	
    public void shutdown() {
        steamUGC.dispose();
        SteamAPI.shutdown();
    }
    
    public static void main(String[] args) throws SteamException {
        // Initialize Steam API
		SteamManager.SteamLibraryLoaderGdx libraryLoader = new SteamManager.SteamLibraryLoaderGdx();
		libraryLoader.setLibraryPath("./lib/natives");
		System.out.println(Paths.get("./lib/natives").toAbsolutePath());
		SteamAPI.loadLibraries(libraryLoader);
        if (!SteamAPI.init()) {
            System.err.println("Failed to initialize Steam API");
            return;
        }

        WorkshopSearcher searcher = new WorkshopSearcher();
        searcher.search();

        // Pump callbacks for a short while to receive query results
        int ticks = 0;
        while (ticks < 20000) {
            SteamAPI.runCallbacks();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            ticks++;
        }

        // Clean up
        searcher.shutdown();
        System.out.println("Finished Steam Workshop search.");
    }
}
