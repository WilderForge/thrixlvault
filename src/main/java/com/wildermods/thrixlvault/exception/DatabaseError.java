package com.wildermods.thrixlvault.exception;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;


/**
 * Thrown to indicate a critical failure related to the main blob database,
 * which may or may not be caused by actual data corruption.
 * <p>
 * This error represents a serious issue encountered while interacting with
 * the blob database. Such a failure may be transient in nature  (e.g., thread interruption,
 * I/O error, or system resource exhaustion) or may be static (database corruption).
 * </p>
 * <p>
 * While this error does not necessarily imply that the database is corrupt,
 * it signifies that the operation cannot proceed safely. Consumers of this
 * error should treat it as fatal and assume that database operations have
 * failed in a way that will compromise the consistency of reconstructed data if
 * the operation was to continue.
 * </p>
 * 
 * @see DatabaseIntegrityError
 * @see Error
 */
@SuppressWarnings("serial")
public class DatabaseError extends Error {

	private final Throwable[] problems;

	public DatabaseError() {
		super();
		problems = new Throwable[]{};
	}
	
	public DatabaseError(String message) {
		super(message);
		problems = new Throwable[] {};
	}
	
	public DatabaseError(Throwable cause) {
		super(cause);
		if(cause != null) {
			problems = new Throwable[] {cause};
		}
		else {
			problems = new Throwable[] {};
		}
	}
	
	public DatabaseError(Throwable... problems) {
		super(getMessages("", problems), problems == null ? null : (Arrays.stream(problems).filter(Objects::nonNull).findFirst().orElse(null)));
		this.problems = problems != null ? problems : new Throwable[]{};
	}
	
	public DatabaseError(String message, Throwable cause) {
		super(getMessages(message, cause), cause);
		if(cause != null) {
			this.problems = new Throwable[] {cause};
		}
		else {
			this.problems = new Throwable[] {};
		}
	}
	
	public DatabaseError(String message, Throwable... problems) {
		super(getMessages(message, problems), problems == null ? null : (Arrays.stream(problems).filter(Objects::nonNull).findFirst().orElse(null)));
		this.problems = problems != null ? problems : new Throwable[]{};
	}
	
	public List<Throwable> getProblems() {
		return Arrays.stream(problems).filter(Objects::nonNull).toList();
	}
	
	/**
	 * Combines a message and an array of throwable causes into a single string.
	 * <p>
	 * This helper method is used internally to construct a full message that includes
	 * the provided message and the messages from all contributing problems.
	 * </p>
	 * 
	 * @param message the base message to include.
	 * @param throwables the array of throwable causes to append.
	 * @return a combined message string.
	 */
	protected static String getMessages(String message, Throwable... throwables) {
		StringBuilder ret = new StringBuilder();
		if(message != null) {
			ret.append(message);
		}
		ret.append('\n');
		
		if(!(throwables == null)) {
			int i = 0;
			for(Throwable t : throwables) {
				if(t == null) {
					continue;
				}
				ret.append("\t\tProblem - ");
				ret.append(t.getClass().getName());
				if(t.getMessage() != null) {
					ret.append(':');
					ret.append(' ');
					ret.append(t.getMessage());
				}
				ret.append('\n');
				if(i >= 30 && throwables.length - i >= 0) {
					ret.append("\t\t...And " + (throwables.length - i) + " additional problems.");
					break;
				}
				i++;
			}
		}
		
		return ret.toString();
	}
}
