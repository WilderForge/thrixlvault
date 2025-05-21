package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.exception.IntegrityProblem;

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
	
	@Override
	public MissingResourceProblem toProblem() {
		return new MissingResourceProblem(this);
	}
	
	public static class MissingResourceProblem implements IntegrityProblem {

		private final String message;
		
		protected MissingResourceProblem(MissingResourceException e) {
			this.message = e.getMessage();
		}
		
		@Override
		public String getMessage() {
			return message;
		}
		
	}
	
}