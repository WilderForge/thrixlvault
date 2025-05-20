package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityProblem;

@SuppressWarnings("serial")
public class MissingVersionException extends MissingResourceException implements IntegrityProblem {
	
	public MissingVersionException() {
		super();
	}
	
	public MissingVersionException(String message) {
		super(message);
	}
	
	public MissingVersionException(String message, Throwable cause) {
		super(message, cause);
	}

}
