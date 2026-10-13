package com.wildermods.thrixlvault;

import java.util.Objects;
import java.util.Set;

/**
 * The local declaration of a named vault context. Each declaration contains
 * one set of file, ignore and tag operations with at most one explicit parent.
 * Tags are opaque, case-sensitive identifiers preserved for custom consumers.
 * Persistence and inheritance resolution belong to {@link Vault}.
 */
final class VaultContext {
	private final String parent;
	private final Set<String> tags;
	private final Set<String> removeTags;
	private final ChrysalisModification modification;

	private VaultContext(String parent, Set<String> tags, Set<String> removeTags,
		ChrysalisModification modification) {
		this.parent = parent == null ? null : Vault.requireContextName(parent);
		this.tags = copyTags(tags);
		this.removeTags = copyTags(removeTags);
		for (String tag : this.tags) {
			if (this.removeTags.contains(tag)) {
				throw new IllegalArgumentException("Cannot add and remove the same tag: " + tag);
			}
		}
		this.modification = Objects.requireNonNull(modification, "modification");
	}

	/** Creates a parentless full snapshot declaration with local tags. */
	public static VaultContext snapshot(Chrysalis snapshot, Set<String> tags) {
		Objects.requireNonNull(snapshot, "snapshot");
		Chrysalis empty = Chrysalis.EMPTY.clone().factory(snapshot.getBlobFactory());
		return modification(null, ChrysalisModification.between(empty, snapshot), tags, Set.of());
	}

	/** Creates a modification declaration with local tag additions and removals. */
	public static VaultContext modification(String parent, ChrysalisModification modification,
		Set<String> tags, Set<String> removeTags) {
		return new VaultContext(parent, tags, removeTags, modification);
	}

	/** @return the explicit parent name, or null to start from empty state */
	public String parent() { return parent; }
	/** @return locally added tags, not inherited tags */
	public Set<String> tags() { return tags; }
	/** @return tags to subtract from the parent; absent tags are harmless */
	public Set<String> removeTags() { return removeTags; }
	/** @return the local operations, also used for parentless roots */
	public ChrysalisModification modification() { return modification; }

	/** Resolves a parentless declaration; children require their owning vault. */
	Chrysalis rootSnapshot() {
		if (parent != null) throw new IllegalStateException("A parent must be resolved through its Vault");
		try {
			return modification.applyTo(Chrysalis.EMPTY.clone().factory(new com.wildermods.masshash.BlobFactory(modification.algorithm())));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Set<String> copyTags(Set<String> tags) {
		Set<String> copy = Set.copyOf(Objects.requireNonNull(tags, "tags"));
		for (String tag : copy) {
			if (tag.isBlank()) {
				throw new IllegalArgumentException("Tags must not be blank");
			}
		}
		return copy;
	}
}
