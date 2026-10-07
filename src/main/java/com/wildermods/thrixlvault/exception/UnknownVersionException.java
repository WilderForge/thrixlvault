package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.exception.IntegrityProblem;

@SuppressWarnings("serial")
public class UnknownVersionException extends IntegrityException {
	
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
	
	public static class MissingVersionProblem implements IntegrityProblem {
		
		private final String message;
		
		private MissingVersionProblem(UnknownVersionException e) {
			this.message = e.getMessage();
		}

		@Override
		public String getMessage() {
			return message;
		}
		
	}

}
