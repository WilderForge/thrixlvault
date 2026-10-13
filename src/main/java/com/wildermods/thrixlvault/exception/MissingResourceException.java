package com.wildermods.thrixlvault.exception;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.masshash.exception.IntegrityProblem;
import com.wildermods.thrixlvault.IResource;

@SuppressWarnings("serial")
public class MissingResourceException extends IntegrityException implements IResource {

	private final Path path;
	
	public MissingResourceException() {
		super();
		path = null;
	}
	
	public MissingResourceException(String message) {
		super(message);
		path = null;
	}
	
	public MissingResourceException(String message, Throwable cause) {
		super(message, cause);
		path = null;
	}
	
	public MissingResourceException(Path resource) {
		this("Missing Resource", resource);
	}
	
	public MissingResourceException(IResource resource) {
		this(resource.getResourceLocation());
	}
	
	public MissingResourceException(String message, Path path) {
		super(message + " - " + path.toString());
		this.path = path;
	}
	
	public MissingResourceException(String message, IResource resource) {
		this(message, resource.getResourceLocation());
	}
	
	public MissingResourceException(String message, Path path, Throwable cause) {
		super(message + " - " + path.toString(), cause);
		this.path = path;
	}
	
	public MissingResourceException(String message, IResource resource, Throwable cause) {
		this(message, resource.getResourceLocation(), cause);
	}
	
	@Override
	public MissingResourceProblem toProblem() {
		return new MissingResourceProblem(this);
	}
	
	@Override
	@Nullable
	public Path getResourceLocation() {
		return path;
	}
	
	public static class MissingResourceProblem implements IntegrityProblem, IResource {

		private final Path resource;
		private final String message;
		
		protected MissingResourceProblem(MissingResourceException e) {
			this.message = e.getMessage();
			this.resource = e.getResourceLocation();
		}
		
		@Override
		public String getMessage() {
			return message;
		}

		@Override
		public @Nullable Path getResourceLocation() {
			return resource;
		}
		
	}
	
}