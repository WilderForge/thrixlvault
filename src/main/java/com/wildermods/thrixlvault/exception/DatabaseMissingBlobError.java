package com.wildermods.thrixlvault.exception;

import com.wildermods.masshash.exception.IntegrityProblem;

/**
 * Thrown to indicate that a required blob is missing from the main blob database.
 * <p>
 * This is a critical integrity violation. The missing blob renders any game definition
 * that uses the blob unable to be verified, and prevents reconstruction of any of
 * the affected game versions.
 * </p>
 * */
@SuppressWarnings("serial")
public class DatabaseMissingBlobError extends DatabaseIntegrityError {

	public DatabaseMissingBlobError() {
		super();
	}
	
	public DatabaseMissingBlobError(String message) {
		super(message);
	}
	
	public DatabaseMissingBlobError(Throwable cause) {
		super(cause);
	}
	
	public DatabaseMissingBlobError(IntegrityProblem... problems) {
		super(problems);
	}
	
	public DatabaseMissingBlobError(String message, Throwable cause) {
		super(message, cause);
	}
	
	public DatabaseMissingBlobError(String message, IntegrityProblem... problems) {
		super(message, problems);
	}
	
	public DatabaseMissingBlobError(String message, Throwable cause, IntegrityProblem... problems) {
		super(message, cause, problems);
	}
	
}
