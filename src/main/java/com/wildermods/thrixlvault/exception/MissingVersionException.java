package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.exception.IntegrityProblem;

@SuppressWarnings("serial")
public class MissingVersionException extends IntegrityException {
	
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
	
	public static class MissingVersionProblem implements IntegrityProblem {
		
		private final String message;
		
		private MissingVersionProblem(MissingVersionException e) {
			this.message = e.getMessage();
		}

		@Override
		public String getMessage() {
			return message;
		}
		
	}

}
