package com.wildermods.thrixlvault.steam;

import com.wildermods.thrixlvault.exception.VersionParsingException;
import com.wildermods.thrixlvault.utils.version.Version;

@FunctionalInterface
public interface IVersioned extends Comparable<IVersioned>{
	public String version();
	
	public default int compareTo(IVersioned other)  {
		Version thisVersion;
		Version otherVersion;
		try {
			thisVersion = Version.parse(version());
		}
		catch(VersionParsingException e) {
			return -1;
		}
		try {
			otherVersion = Version.parse(other.version());
		}
		catch(VersionParsingException e) {
			return 1;
		}
		return thisVersion.compareTo(otherVersion);

	}
}
