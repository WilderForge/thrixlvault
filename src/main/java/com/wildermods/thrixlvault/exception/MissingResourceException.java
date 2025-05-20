package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityException;

@SuppressWarnings("serial")
public class MissingResourceException extends IntegrityException {

	public MissingResourceException() {
		super();
	}
	
	public MissingResourceException(String message) {
		super(message);
	}
	
	public MissingResourceException(String message, Throwable cause) {
		super(message, cause);
	}
	
}