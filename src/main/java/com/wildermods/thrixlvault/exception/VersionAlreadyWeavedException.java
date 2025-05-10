package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityException;

@SuppressWarnings("serial")
public class VersionAlreadyWeavedException extends IntegrityException {

	public VersionAlreadyWeavedException(String message) {
		super(message);
	}
	
}
