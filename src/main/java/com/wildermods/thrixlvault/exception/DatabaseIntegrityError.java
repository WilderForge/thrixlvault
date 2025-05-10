package com.wildermods.thrixlvault.exception;

/**
 * Thrown to indicate a critical integrity violation in the main blob database.
 * <p>
 * This error signals that the blob database has become corrupt, resulting in an
 * unrecoverable state where the integrity of any reconstructed game instance
 * can no longer be verified.
 * </p>
 * <p>
 * This error should be used when the database, which is used for checking
 * integrity and reconstructing game instances, has itself become corrupt.
 * </p>
 * <p>
 * Continuing will lead to corruption or other errors in reconstructed
 * game instances.
 * </p>
 * 
 * @see Error
 */
@SuppressWarnings("serial")
public class DatabaseIntegrityError extends DatabaseError {

	public DatabaseIntegrityError() {
		super();
	}
	
	public DatabaseIntegrityError(String message) {
		super(message);
	}
	
	public DatabaseIntegrityError(Throwable cause) {
		super(cause);
	}
	
	public DatabaseIntegrityError(Throwable... problems) {
		super(problems);
	}
	
	public DatabaseIntegrityError(String message, Throwable cause) {
		super(message, cause);
	}
	
	public DatabaseIntegrityError(String message, Throwable... problems) {
		super(message, problems);
	}

}

