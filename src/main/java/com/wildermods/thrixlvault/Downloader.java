package com.wildermods.thrixlvault;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.wildermods.thrixlvault.steam.IDownload;
import com.wildermods.thrixlvault.steam.IDownloadable;

/**
 * Base class representing a generic downloader for a set of {@link IDownloadable} items.
 * 
 * <p>This class provides a framework for performing downloads and tracking their progress.
 * Subclasses must implement the actual download logic in {@link #runImpl()} and optionally
 * provide support for accessing downloads while they are in progress through
 * {@link #getDownloadsInProgress()}.</p>
 * 
 * <p>The class distinguishes between downloads that are still in progress and those
 * that are fully completed:</p>
 * <ul>
 *     <li>{@link #getDownloads()} returns the set of downloads either in progress or completed.
 *         By default, it delegates to {@link #getDownloadsInProgress()} if the downloads are not
 *         finished yet, and to {@link #getFinishedDownloads()} once complete.</li>
 *     <li>{@link #getFinishedDownloads()} returns the completed downloads only and throws an
 *         {@link IllegalStateException} if called before completion.</li>
 * </ul>
 * 
 * <p>The {@link #run()} method executes the download synchronously and blocks until all downloads
 * are finished. After completion, the results are cached as an immutable set. If asynchronous or
 * incremental download reporting is desired, subclasses may override {@link #getDownloadsInProgress()}.</p>
 * 
 * @param <Downloadable> The type of items to download, must implement {@link IDownloadable}.
 * @param <Download> The type representing a completed download, must implement {@link IDownload}.
 */
public abstract class Downloader<Downloadable extends IDownloadable, Download extends IDownload> {
	
	private final Object runLock = new Object();
	
	protected final Set<Downloadable> downloadables;
	private volatile Set<Download> finishedDownloads;
	private volatile boolean finished = false;
	
	public Downloader(Collection<Downloadable> downloadables) {
		this.downloadables = ImmutableSet.copyOf(downloadables);
	}
	
    /**
     * Runs the downloader synchronously and blocks until all downloads are complete.
     * 
     * @return an immutable set of completed downloads.
     * @throws IOException 
     */
	public final Set<Download> run() throws IOException {
		synchronized(runLock) {
			finishedDownloads = ImmutableSet.copyOf(runImpl());
			finished = true;
			return finishedDownloads;
		}
	}
	
	protected abstract Set<Download> runImpl() throws IOException;
	
	public final boolean isFinished() {
		return finished;
	}
	
	public final Set<Download> getFinishedDownloads() {
		if(finished) {
			return finishedDownloads;
		}
		throw new IllegalStateException("Download in progress");
	}
	
	public final Set<Download> getDownloads() throws UnsupportedOperationException {
		if(finished) {
			return getFinishedDownloads();
		}
		return getDownloadsInProgress();
	}
	
	protected abstract ImmutableSet<Download> getDownloadsInProgress() throws UnsupportedOperationException;
	
}
