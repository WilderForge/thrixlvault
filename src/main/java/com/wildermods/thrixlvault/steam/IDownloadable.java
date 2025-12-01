package com.wildermods.thrixlvault.steam;

import com.wildermods.thrixlvault.Downloader;

public interface IDownloadable extends INamed, IVaultable, IVersioned {
	
	public default String version() {
		return "0.0.0";
	}
	
	/**
	 * Returns the reason this item cannot currently be downloaded.
	 * <p>
	 * This method is intended to be checked <em>before</em> attempting a download. 
	 * {@link Downloader} instances should skip downloading any downloadables that return
	 * a non-null value.
	 * <p>
	 * If the return value is {@code null}, the item may be downloaded.  
	 * If a non-{@code null} string is returned, the download is blocked, and the string
	 * explains why (e.g., "User does not have a license", "only 'public' branches may be
	 * downloaded", etc).
	 * </p>
	 * <p>
	 * Note: This method only controls downloading. If the item is already downloaded,
	 * you may still be able to unvault it even if this method returns a non-null value.
	 * </p>
	 * Example usage:
	 * <pre>{@code
	 * IDownloadable item = ...;
	 * String reason = item.downloadBlockedReason();
	 * if (reason != null) {
	 *     System.out.println("Cannot download: " + reason);
	 *     return;
	 * }
	 * // safe to proceed with download
	 * download(item);
	 * }</pre>
	 * </p>
	 *
	 * @return {@code null} if downloading is allowed; otherwise a human-readable reason
	 *         why the download is blocked
	 */
	public default String downloadBlockedReason() {
		return null;
	}
	
}
