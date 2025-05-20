package com.wildermods.thrixlvault.exception;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import com.wildermods.masshash.exception.IntegrityProblem;


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

	private final IntegrityProblem[] problems;
	
	/**
	 * Default constructor for DatabaseError.
	 * <p>
	 * This creates a new DatabaseError with no detail message or problem.
	 * </p>
	 */
	public DatabaseError() {
		this(null, null, (IntegrityProblem[])null);
	}
	
	/**
	 * Constructs a DatabaseError with a specified detail message.
	 * <p>
	 * This creates a new DatabaseError with the specified detail message, 
	 * which provides more information about the problem of the exception.
	 * </p>
	 * 
	 * @param message the detail message that explains the problem of the exception.
	 */
	public DatabaseError(String message) {
		this(message, null, (IntegrityProblem[])null);
	}
	
	public DatabaseError(Throwable cause) {
		this(null, cause, (IntegrityProblem[])null);
	}
	
	/**
	 * Constructs a DatabaseError from one or more {@link IntegrityProblem}s.
	 * <p>
	 * This constructor is useful when the exception is caused by known integrity
	 * problems and no additional message or cause is needed.
	 * </p>
	 * 
	 * @param problems one or more integrity problems that contributed to the failure.
	 */
	public DatabaseError(IntegrityProblem... problems) {
		this(null, null, problems);
	}
	
	/**
	 * Constructs a DatabaseError with a message and a cause.
	 * 
	 * @param message the detail message explaining the context of the failure.
	 * @param cause the underlying cause of the exception.
	 */
	public DatabaseError(String message, Throwable cause) {
		this(message, cause, (IntegrityProblem[])null);
	}
	
	
	/**
	 * Constructs a DatabaseError from one or more {@link IntegrityProblem}s.
	 * <p>
	 * This constructor is useful when the exception is caused by known integrity
	 * problems and no additional message or cause is needed.
	 * </p>
	 * 
	 * @param message the detail message explaining the context of the failure.
	 * @param problems one or more integrity problems that contributed to the failure.
	 */
	public DatabaseError(String message, IntegrityProblem... problems) {
		this(message, null, problems);
	}
	
	
	/**
	 * Constructs a DatabaseError with a detail message and multiple causes.
	 * <p>
	 * This is useful when several issues are discovered,
	 * and you want to report all of them at once.
	 * </p>
	 * 
	 * @param message the detail message describing the exception.
	 * @param problems the array of underlying problems contributing to the exception.
	 */
	public DatabaseError(String message, Throwable cause, IntegrityProblem... problems) {
		this(message, cause, computeProblems(problems), false);
	}
	
	private DatabaseError(String message, Throwable cause, IntegrityProblem[] problems, boolean unused) {
		super(getMessages(message, problems), cause);
		this.problems = problems;
	}
	
	/**
	 * Returns a stream of all {@link IntegrityProblem}s associated with this exception.
	 * <p>
	 * This allows consumers to process or inspect individual problems.
	 * </p>
	 * 
	 * @return a Stream of IntegrityProblem instances.
	 */
	public Stream<IntegrityProblem> getProblems() {
		return Arrays.stream(problems);
	}
	
	private static IntegrityProblem[] computeProblems(IntegrityProblem... problems) {
		if(problems == null) {
			return problems = new IntegrityProblem[]{};
		}
		return problems = Arrays.stream(problems).filter(Objects::nonNull).toList().toArray(new IntegrityProblem[] {});
	}
	
	/**
	 * Combines a message and an array of {@link IntegrityProblem}s into a single string.
	 * <p>
	 * This helper method is used internally to construct a full message that includes
	 * the provided message and the messages from all contributing problems. It truncates
	 * the output if the number of problems exceeds a threshold.
	 * </p>
	 * 
	 * @param message the base message to include.
	 * @param problems the array of integrity problems to include in the output.
	 * @return a combined message string.
	 */
	protected static String getMessages(String message, IntegrityProblem... problems) {
		StringBuilder ret = new StringBuilder();
		if(message != null) {
			ret.append(message);
		}
		ret.append('\n');
		
		if(!(problems == null)) {
			int i = 0;
			for(IntegrityProblem t : problems) {
				if(t == null) {
					continue;
				}
				ret.append("\t\tProblem - ");
				if(t instanceof Enum) {
					ret.append(((Enum<?>)t).name());
					ret.append(':');
				}
				else if(t.getClass().isSynthetic()) { 
					//if it's a lambda or inner class, don't print the class name
				}
				else {
					ret.append(t.getClass().getSimpleName());
					ret.append(':');
				}
				if(t.getMessage() != null) {
					ret.append(' ');
					ret.append(t.getMessage().trim());
				}
				ret.append('\n');
				if(i >= 30 && problems.length - i >= 0) {
					ret.append("\t\t...And " + (problems.length - i) + " additional problems.");
					break;
				}
				i++;
			}
		}
		
		return ret.toString();
	}

}
