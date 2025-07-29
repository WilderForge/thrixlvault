package com.wildermods.thrixlvault.steam;

import java.nio.file.Path;
import java.util.Objects;

public interface ISteamDownloadable extends IDownloadable, INamed, IGame, IDepot, IManifest, IVaultable {

	public default String name() {
		return manifest() + "";
	}
	
	public default String getDownloadCommand() {
		return "download_depot " + game() + " " + depot() + " " + manifest() + " 0 ";
	}
	
	public default String getDownloadCommand(Path path) {
		return getDownloadCommand() + path.toAbsolutePath();
	}
	
	@Override
	public default String version() {
		return IManifest.super.version();
	}

	public static boolean isEqual(ISteamDownloadable downloadable1, ISteamDownloadable downloadable2) {
		return downloadable1.game() == downloadable2.game() && downloadable1.manifest() == downloadable2.manifest() && downloadable1.depot() == downloadable2.depot();
	};
	
	public static int hashCode(ISteamDownloadable downloadable) {
		return Objects.hash(downloadable.game(), downloadable.depot(), downloadable.manifest());
	}
	
}
