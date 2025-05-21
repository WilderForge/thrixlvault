package com.wildermods.thrixlvault.exception;

@SuppressWarnings("serial")
public class MissingVersionException extends MissingResourceException {
	
	public MissingVersionException() {
		super();
	}
	
	public MissingVersionException(String message) {
		super(message);
	}
	
	public MissingVersionException(String message, Throwable cause) {
		super(message, cause);
	}
	
	@Override
	public MissingVersionProblem toProblem() {
		return new MissingVersionProblem(this);
	}
	
	public static class MissingVersionProblem extends MissingResourceProblem {
		
		private MissingVersionProblem(MissingVersionException e) {
			super(e);
		}
		
	}

}
