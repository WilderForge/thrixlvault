package com.wildermods.thrixlvault.exception;

@SuppressWarnings("serial")
public class VersionParsingException extends Exception {

	public VersionParsingException() {}
	
	public VersionParsingException(Throwable t) {
		super(t);
	}
	
	public VersionParsingException(String s) {
		super(s);
	}
	
	public VersionParsingException(String s, Throwable t) {
		super(s, t);
	}
	
}
