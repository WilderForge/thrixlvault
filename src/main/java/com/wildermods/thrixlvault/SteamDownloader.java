package com.wildermods.thrixlvault;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.login.CredentialException;
import javax.security.sasl.AuthenticationException;

import com.google.common.collect.ImmutableSet;
import com.wildermods.thrixlvault.steam.CompletedDownload;
import com.wildermods.thrixlvault.steam.FailedDownload;
import com.wildermods.thrixlvault.steam.IManifest;
import com.wildermods.thrixlvault.steam.ISteamDownload;
import com.wildermods.thrixlvault.steam.ISteamDownloadable;
import com.wildermods.thrixlvault.utils.FileUtil;

import static com.wildermods.thrixlvault.SteamDownloader.SteamState.*;

public class SteamDownloader extends Downloader<ISteamDownloadable, ISteamDownload> {

	private static final Object GLOBAL_RUN_LOCK = new Object(); // So we only have one instance of steamcmd operating at a time.

	public static final Path DEFAULT_APP_INSTALL_DIR = Path.of(System.getProperty("user.home")).resolve("thrixlvault").resolve("current_download");
	private final Path installDir;
	private final String username;
	private final HashMap<IManifest, ISteamDownload> processedDownloads = new HashMap<IManifest, ISteamDownload>();

	private volatile Process process;
	private volatile SteamState prevState = SteamState.UNINITIALIZED;
	private volatile SteamState steamState = SteamState.UNINITIALIZED;
	private volatile boolean userInput = false;
	private volatile Instant lastResponse = Instant.now();
	private volatile Duration hangTimeout = Duration.ofSeconds(30);
	private volatile Duration downloadTimeout = Duration.ofMinutes(7);
	private volatile Consumer<ISteamDownload> onManifestDownload = (d) -> {};
	
	private final Object interruptLock = new Object();
	private final Object stateLock = new Object();
	private volatile boolean interrupt = false;;
	
	private volatile ISteamDownloadable currentDownload;

	private static final Pattern ERROR_REGEX = Pattern.compile("ERROR \\((?<error>.*)\\)\\n");

	public SteamDownloader(String username, Collection<ISteamDownloadable> downloads) {
		super(downloads);
		this.username = username;
		this.installDir = DEFAULT_APP_INSTALL_DIR;
	}
	
	public SteamDownloader(String username, Collection<ISteamDownloadable> downloads, Path installDir) {
		super(downloads);
		this.username = username;
		this.installDir = installDir;
	}
	
	public SteamDownloader setHangTimeout(Duration hangTimeout) {
		this.hangTimeout = hangTimeout;
		return this;
	}
	
	public SteamDownloader setDownloadTimeout(Duration downloadTimeout) {
		this.downloadTimeout = downloadTimeout;
		return this;
	}
	
	public SteamDownloader setConsumer(Consumer<ISteamDownload> onManifestDownload) {
		this.onManifestDownload = onManifestDownload;
		return this;
	}
	
	@Override
	public Set<ISteamDownload> runImpl() throws IOException {
		synchronized (GLOBAL_RUN_LOCK) {
			System.out.println("[DOWNLOADER] Acquired global run lock");
			return this.runInternal(onManifestDownload, hangTimeout, downloadTimeout);
		}
	}

	private Set<ISteamDownload> runInternal(Consumer<ISteamDownload> onManifestDownload, Duration hangTimeout, Duration downloadTimeout) throws IOException {
		ProcessBuilder processBuilder = new ProcessBuilder("steamcmd");
		processBuilder.redirectErrorStream(true); // Merge stdout and stderr
		process = processBuilder.start();

		InputStream inputStream = process.getInputStream();
		OutputStream outputStream = process.getOutputStream();

		//Start a thread to continuously read and print SteamCMD output byte by byte
		//WE MUST READ BYTE BY BYTE, DO NOT USE SCANNER OR ANY OTHER METHOD OF READING
		Thread outputThread = new Thread(() -> {
			try {
				int byteRead;
				StringBuilder line = new StringBuilder();
				setState(INITIALIZE);
				Iterator<ISteamDownloadable> downloadables = this.downloadables.iterator();
				int downloadProgress = 0;
				currentDownload = null;
				boolean next = false;
				
				while ((byteRead = inputStream.read()) != -1 || next) {
					responded();
					char outputChar = 0;
					if(!next) {
						outputChar = (char) byteRead;
						// Print the byte as a character to the console
						System.out.print(outputChar);
						line.append(outputChar);
					}

					// Detect "Steam>" prompt for user input
					if (endsWith(line, "Steam>") || next) {
						if(next) {
							next = false;
						}
						SteamState state = getState();
						if(state == INITIALIZE) {
							setState(SETUP_DIR);
							send("force_install_dir " + installDir);
						}
						else if(state == SETUP_DIR) {
							setState(LOGGING_IN);
							if(username != null) {
								send("login " + username);
							}
							else {
								throw new CredentialException("No username supplied. You must supply a `username=[USERNAME]` as a launch argument");
							}
						}
						else if (state == LOGGING_IN) {
							setState(DOWNLOADING);
							System.out.println("\nThere are " + this.downloadables.size() + " manifests to download.");
							next = true;
							continue;
						}
						else if (state == DOWNLOADING) {
							if(currentDownload != null) {
								downloadProgress++;
								ISteamDownload download = processedDownloads.get(currentDownload);
								if(download == null) {
									putDownload(onManifestDownload, currentDownload, new CompletedDownload(currentDownload, installDir));
									System.out.println("\n[" + downloadProgress + "/" + this.downloadables.size() + "] Downloaded " + currentDownload);
								}
								else {
									if(!(download instanceof FailedDownload)) {
										throw new AssertionError(currentDownload + " already completed??");
									}
								}
							}
							if(downloadables.hasNext()) {
								currentDownload = downloadables.next();
								if(Files.exists(installDir)) {
									System.out.println("[" + downloadProgress + "/" + this.downloadables.size() + "] Clearing " + installDir);
									FileUtil.deleteDirectory(installDir);
								}
								for(Path steamPatchFile : Files.walk(installDir.getParent(), 1) //delete steam patch files from the parent directory so a new download attempt always starts fresh
									.filter((path) -> { 
										return path.startsWith("state") && path.endsWith(".patch");
									}
								).toList()) {
									if(Files.deleteIfExists(steamPatchFile)){
										System.out.println("Deleted steam patch file: " + steamPatchFile);
									}
								}
								System.out.println("[" + downloadProgress + "/" + this.downloadables.size() + "] Downloading " + currentDownload);
								send(currentDownload.getDownloadCommand(installDir));
							}
							else {
								next = true;
								setState(FINISHED);
								continue;
							}
						}
						line = new StringBuilder();
					}
					
					/**
					 * Error and exception handling
					 */
					{
						checkError(line);
						if(outputChar == '\n') {
							line = new StringBuilder();
						}
						if(getState() == FAILURE) {
							if(prevState == DOWNLOADING && currentDownload != null) {
								putDownload(onManifestDownload, currentDownload, new FailedDownload(currentDownload, installDir, new Exception("Could not download " + currentDownload + ". Reason: " + line.toString().trim())));
								setState(DOWNLOADING);
								Thread.sleep(1000);
							}
							else {
								throw new Error("SteamCMD encountered an error during phase " + prevState + " - " + line.toString().trim());
							}
						}
					}
					/*
					 * Ending the program
					 */
					SteamState state = getState();
					assert state != UNINITIALIZED;
					if(state == FINISHED || state == FAILURE) {
						if(process.isAlive()) {
							send("exit"); //even if the process is killed between the check and executing 'exit', it's fine to just print the stack trace.
						}
						break;
					}
				}
			} catch (Throwable t) {
				System.err.println();
				t.printStackTrace();
				if(!processedDownloads.containsKey(currentDownload)) {
					processedDownloads.put(currentDownload, new FailedDownload(currentDownload, installDir, t));
				}
				process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly); //for some reason a process leak occurs if we don't do this
				process.destroyForcibly();
			}
		});

		outputThread.setName("STEAMCMD INTEROP");
		outputThread.start(); // Start reading SteamCMD output
		
		Thread watchdog = new Thread(() -> {
			Instant now;
			while(process.isAlive()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					break;
				}
				now = Instant.now();
				Duration sinceLastResponse = Duration.between(lastResponse, now);
				switch(getState()) {
					case DOWNLOADING:
						if(sinceLastResponse.compareTo(downloadTimeout) > 0) {
							System.out.println("[WATCHDOG] Download took longer than  " + downloadTimeout);
							synchronized(interruptLock) {
								System.out.println("Setting interrupt to true");
								interrupt = true;
							}
							process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly); //for some reason a process leak occurs if we don't do this
							process.destroyForcibly();
							try {
								inputStream.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
						break;
					default:
						if(sinceLastResponse.compareTo(hangTimeout) > 0) {
							System.out.println("[WATCHDOG] Did not receive a response from steamcmd for " + hangTimeout);
							synchronized(interruptLock) {
								interrupt = true;
							}
							process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
							process.destroyForcibly();
							try {
								inputStream.close();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
						break;
					}
			}
		});
		
		watchdog.setName("DOWNLOADER WATCHDOG");
		watchdog.setDaemon(true);
		watchdog.start();

		// !! DEVELOPMENT ONLY !!
		// Start a thread to handle user input, for development and debugging purposes
		// This thread should never be enabled in production.
		// It breaks automation state assumptions.
		if(userInput) {
			Thread inputThread = new Thread(() -> {
				try(Scanner scanner = new Scanner(System.in)) {
					BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
		
					while (process.isAlive()) {
						String userInput = scanner.nextLine(); // Get user input
						try {
							writer.write(userInput + "\n"); // Write to SteamCMD
							writer.flush(); // Ensure it's sent immediately
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			});
	
			inputThread.setName("DOWNLOADER DEV INPUT");
			inputThread.setDaemon(true);
			inputThread.start(); // Start handling user input
		}

		try {
			// Wait for the output thread to finish
			outputThread.join();
	
			// Wait for the process to finish
			int exitCode = process.waitFor();
			synchronized(interruptLock) {
				if(interrupt) {
					InterruptedException e = new InterruptedException();
					if(steamState == DOWNLOADING) {
						if(!processedDownloads.containsKey(currentDownload)) {
							this.processedDownloads.put(currentDownload, new FailedDownload(currentDownload, installDir, e));
						}
					}
					throw e;
				}
			}
			System.out.println("SteamCMD exited with exit code " + exitCode);
		}
		catch(InterruptedException e) {
			e.printStackTrace();
			process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly); //for some reason a process leak occurs if we don't do this
			process.destroyForcibly();
		}

		
		return Set.copyOf(processedDownloads.values());
	}
	
	@Override
	protected ImmutableSet<ISteamDownload> getDownloadsInProgress() throws UnsupportedOperationException {
		throw new UnsupportedOperationException();
	}
	
	private void putDownload(Consumer<ISteamDownload> onManifestDownload, IManifest manifest, ISteamDownload download) {
		this.processedDownloads.put(manifest, download);
		onManifestDownload.accept(download);
	}
	
	private void responded() {
		lastResponse = Instant.now();
	}
	
	private void checkError(StringBuilder line) throws Throwable {
		if (endsWith(line, "Cached credentials not found.")) {
			setState(FAILURE);
			throw new CredentialException("You must sign in on SteamCMD at least once on this machine so your cached credintials can be used.");
		}
		if (endsWith(line, "This computer has not been authenticated for your account using Steam Guard.")) {
			setState(FAILURE);
			throw new AuthenticationException("This computer has not been authenticated for your account using Steam Guard. You must authenticate with steam guard at least once on this machine.");
		}
		if (match(line, ERROR_REGEX, "error") != null) {
			setState(FAILURE);
		}
		if(endsWith(line, "steamcmd has been disconnected")) { //this message will have an additional reason
			setState(FAILURE); //set the state to failure, so when newline gets output the full reason is shown
		}
		if (endsWith(line, "Depot download failed")) {
			setState(FAILURE);
		}

	}
	
	private static final boolean endsWith(StringBuilder line, String text) {
		return line.toString().endsWith(text);
	}
	
	private static final String match(StringBuilder line, Pattern regex, String group) {
		Matcher matcher = regex.matcher(line);
		if(matcher.find()) {
			return matcher.group(group);
		}
		return null;
	}

	private final void send(String command) throws IOException {
		OutputStream outputStream = process.getOutputStream();
	   	BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
	   	System.out.println("[DOWNLOADER/COMMAND] Executing '" + command + "'");
		writer.write(command + "\n");
		writer.flush();
	}
	
	public SteamState getState() {
		return steamState;
	}
	
	private SteamState setState(SteamState newState) {
		synchronized(stateLock) {
			prevState = getState();
			steamState = newState;
			System.out.println("\n[DOWNLOADER/STATE]: Steam state changed from " + prevState + " to " + newState);
			return prevState;
		}
	}

	public enum SteamState {
		UNINITIALIZED, INITIALIZE, SETUP_DIR, LOGGING_IN, DOWNLOADING, FINISHED, FAILURE
	}

}