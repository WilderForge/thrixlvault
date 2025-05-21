package com.wildermods.thrixlvault.exception;

@SuppressWarnings("serial")
public class UnknownVersionException extends MissingResourceException {
	
	public UnknownVersionException() {
		super();
	}
	
	public UnknownVersionException(String message) {
		super(message);
	}
	
	public UnknownVersionException(String message, Throwable cause) {
		super(message, cause);
	}
	
	@Override
	public MissingVersionProblem toProblem() {
		return new MissingVersionProblem(this);
	}
	
	public static class MissingVersionProblem extends MissingResourceProblem {
		
		private MissingVersionProblem(UnknownVersionException e) {
			super(e);
		}
		
	}

}
