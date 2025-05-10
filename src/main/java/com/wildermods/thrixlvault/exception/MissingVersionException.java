package com.wildermods.thrixlvault.exception;

@SuppressWarnings("serial")
public class MissingVersionException extends MissingResourceException {
	
	public MissingVersionException() {
		super();
	}
	
	public MissingVersionException(String message) {
		super(message);
	}
	
	public MissingVersionException(Throwable cause) {
		super(cause);
	}
	
	public MissingVersionException(String message, Throwable cause) {
		super(message, cause);
	}

}
