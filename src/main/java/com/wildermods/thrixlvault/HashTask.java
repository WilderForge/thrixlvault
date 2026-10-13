package com.wildermods.thrixlvault;

import java.io.IOException;
import java.nio.file.Path;

import com.wildermods.masshash.Hash;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.exception.IntegrityProblem;
import com.wildermods.thrixlvault.exception.DatabaseError;
import com.wildermods.thrixlvault.exception.DatabaseError.DatabaseProblem;

/**
 * Represents an operation performed on a hashed object within a vault.
 * <p>
 * A {@code HashTask} is supplied with the {@link Hash} identifying the object,
 * the path to the vault, the associated {@link Chrysalis}, and a
 * {@link ProblemHandler} used to process integrity problems encountered while
 * performing the task.
 * <p>
 * The functional method of this interface is
 * {@link #call(Hash, Path, Chrysalis, ProblemHandler)}.
 */
@FunctionalInterface
public interface HashTask {
	
	
	/**
	 * The default problem handler used by
	 * {@link #call(Hash, Path, Chrysalis)}.
	 * <p>
	 * This handler propagates throwable integrity problems directly, converts
	 * {@link DatabaseProblem database problems} to {@link DatabaseError}s, and
	 * wraps other integrity problems in an {@link IntegrityException}.
	 */
	public static final ProblemHandler DEFAULT_PROBLEM_HANDLER = new Internal.DefaultProblemHandler();
	
	/**
	 * Performs this task using the {@link #DEFAULT_PROBLEM_HANDLER default problem handler}.
	 *
	 * @param hash the hash identifying the object on which to operate
	 * @param vaultPath the path to the vault
	 * @param chrysalis the chrysalis associated with the object
	 *
	 * @throws IOException if an I/O error occurs while performing the task
	 * @throws IntegrityException if an integrity problem is encountered
	 */
	default void call(Hash hash, Path vaultPath, Chrysalis chrysalis) throws IOException, IntegrityException {
		call(hash, vaultPath, chrysalis, DEFAULT_PROBLEM_HANDLER);
	}
	
	/**
	 * Performs this task using the specified problem handler.
	 * <p>
	 * Problems encountered during the operation may be passed to
	 * {@code problemHandler}. If the handler returns normally, the problem is
	 * considered handled and the task should continue where possible.
	 * <p>
	 * Implementations <b>must</b> ensure that execution following a handled problem
	 * resumes from a valid state and does not accidentally bypass work necessary
	 * to complete the operation.
	 * <p>
	 * A {@link HashTask} must preserve the semantic context of a handled problem.
	 * In particular, the task must be structured so that, if an exception is
	 * caught and passed to a problem handler and the handler returns normally,
	 * execution resumes at a valid continuation point. Task implementations should
	 * therefore avoid placing unrelated or necessary subsequent operations inside
	 * a single {@code try} block when catching an exception from an earlier
	 * operation.
	 * <p>
	 * For example, the following structure is generally incorrect:
	 * <pre>{@code
	 * try {
	 *     performStepA();
	 *     performStepB();
	 * }
	 * catch (SomeException e) {
	 *     problemHandler.onProblem(e);
	 * }
	 * }</pre>
	 * If {@code performStepA()} throws and the handler successfully resolves the
	 * problem without rethrowing it, {@code performStepB()} is still skipped even
	 * though the task is expected to continue.
	 * <p>
	 * Instead, exception handling should generally be scoped to the operation whose
	 * failure is being handled:
	 * <pre>{@code
	 * try {
	 *     performStepA();
	 * }
	 * catch (SomeException e) {
	 *     problemHandler.onProblem(e);
	 * }
	 *
	 * try {
	 *     performStepB();
	 * }
	 * catch (SomeException e) {
	 *     problemHandler.onProblem(e);
	 * }
	 * }</pre>
	 * This ensures that, when the problem handler returns normally,
	 * {@code performStepB()} is still executed and the task continues from a
	 * well-defined state.
	 * <p>
	 */
	void call(Hash hash, Path vaultPath, Chrysalis chrysalis, ProblemHandler problemHandler) throws IOException, IntegrityException;
	
	/**
	 * Handles problems encountered while executing a {@link HashTask}.
	 * <p>
	 * Implementations may resolve, ignore, record, convert, or propagate reported
	 * problems. Returning normally indicates that the problem has been handled and
	 * that execution may continue.
	 * <p>
	 * {@link HashTask} implementations are responsible for ensuring that execution
	 * can continue from a valid state after a handler returns normally. See
	 * {@link HashTask#call(Hash, Path, Chrysalis, ProblemHandler)} for details.
	 */
	@FunctionalInterface
	public static interface ProblemHandler {
		
		/**
		 * Handles a Throwable.
		 * <p>
		 * The default implementation rethrows the supplied throwable unchanged.
		 *
		 * @param t the throwable to handle
		 *
		 * @throws Throwable the supplied throwable
		 */
		public default void onProblem(Hash hash, Throwable t) throws Throwable {
			throw t;
		}
		
		/**
		 * Handles an integrity problem.
		 *
		 * @param p the integrity problem to handle
		 *
		 * @throws Throwable if handling the problem results in an exception or error
		 */
		public void onProblem(Hash hash, IntegrityProblem p) throws Throwable;
		
	}
	
	/**
	 * Contains internal implementation details used by {@link HashTask}.
	 * <p>
	 * This class is not intended for direct use by consumers of the API.
	 */
	@Deprecated
	public static class Internal {
		
		private static final class DefaultProblemHandler implements ProblemHandler {

			@Override
			public void onProblem(Hash hash, IntegrityProblem p) throws Throwable {
				if(p instanceof Throwable) {
					throw (Throwable)p;
				}
				if(p instanceof DatabaseProblem) {
					throw new DatabaseError(p);
				}
				throw new IntegrityException(p);
			}
			
		}
	}
}
