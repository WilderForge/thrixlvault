package com.wildermods.thrixlvault;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The local declaration of a named vault context. A declaration contains either
 * a full snapshot or a modification with at most one explicit parent.
 * Tags are opaque, case-sensitive identifiers preserved for custom consumers.
 * Persistence and inheritance resolution belong to {@link Vault}.
 */
final class VaultContext {
	private final String parent;
	private final Set<String> tags;
	private final Set<String> removeTags;
	private final Chrysalis snapshot;
	private final ChrysalisModification modification;

	private VaultContext(String parent, Set<String> tags, Set<String> removeTags,
		Chrysalis snapshot, ChrysalisModification modification) {
		this.parent = parent == null ? null : Vault.requireContextName(parent);
		this.tags = copyTags(tags);
		this.removeTags = copyTags(removeTags);
		for (String tag : this.tags) {
			if (this.removeTags.contains(tag)) {
				throw new IllegalArgumentException("Cannot add and remove the same tag: " + tag);
			}
		}
		this.snapshot = snapshot == null ? null : snapshot.clone();
		this.modification = modification;
	}

	/** Creates a parentless full snapshot declaration with local tags. */
	public static VaultContext snapshot(Chrysalis snapshot, Set<String> tags) {
		return new VaultContext(null, tags, Set.of(), Objects.requireNonNull(snapshot, "snapshot"), null);
	}

	/** Creates a modification declaration with local tag additions and removals. */
	public static VaultContext modification(String parent, ChrysalisModification modification,
		Set<String> tags, Set<String> removeTags) {
		return new VaultContext(parent, tags, removeTags, null,
			Objects.requireNonNull(modification, "modification"));
	}

	/** @return the explicit parent name, or null to start from empty state */
	public String parent() { return parent; }
	/** @return locally added tags, not inherited tags */
	public Set<String> tags() { return tags; }
	/** @return tags to subtract from the parent; absent tags are harmless */
	public Set<String> removeTags() { return removeTags; }
	/** @return whether this declaration contains a full snapshot */
	public boolean isSnapshot() { return snapshot != null; }
	/** @return a distinct snapshot view, when this is a full declaration */
	public Optional<Chrysalis> snapshot() {
		return snapshot == null ? Optional.empty() : Optional.of(snapshot.clone());
	}
	/** @return the local modification, when this is a modification declaration */
	public Optional<ChrysalisModification> modification() { return Optional.ofNullable(modification); }

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
