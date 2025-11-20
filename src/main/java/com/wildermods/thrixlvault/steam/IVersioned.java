package com.wildermods.thrixlvault.steam;

import com.wildermods.thrixlvault.exception.VersionParsingException;
import com.wildermods.thrixlvault.utils.version.Version;

@FunctionalInterface
public interface IVersioned extends Comparable<IVersioned>{
	public String version();
	
	public default Version asVersion() throws VersionParsingException {
		if(this instanceof Version) {
			return (Version) this;
		}
		return Version.parse(version());
	}
	
	public default int compareTo(IVersioned other)  {
		Version thisVersion;
		Version otherVersion;
		try {
			thisVersion = asVersion();
		}
		catch(VersionParsingException e) {
			return -1;
		}
		try {
			otherVersion = other.asVersion();
		}
		catch(VersionParsingException e) {
			return 1;
		}
		return thisVersion.compareTo(otherVersion);

	}
}
