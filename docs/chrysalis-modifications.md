# Chrysalis modifications

`ChrysalisModification` describes changes to expected files. Changes are keyed
by relative path; the resolved `Chrysalis` still uses a hash-to-paths multimap.
Replacing or removing one copy of a blob does not affect copies at other paths.

```java
ChrysalisizedVault vanilla = vault.chrysalisize(manifest);
ChrysalisModification modification = ChrysalisModification
    .builder(vanilla.getChrysalis().getBlobFactory().algorithm())
    .replace(Path.of("lib/example.jar"), originalHash, modifiedHash)
    .replace(Path.of("other/example.jar"), originalHash, modifiedHash)
    .remove(Path.of("lib/obsolete.jar"), obsoleteHash)
    .add(Path.of("lib/wilderforge.jar"), loaderHash)
    .build();

vault.saveModification(manifest, "modded", "blobs", modification);
ChrysalisizedVault modded = vault.chrysalisize(manifest, "modded");
modded.export(freshDestination, true);
```

Hashes must use the base snapshot's algorithm. Additions require absent paths;
replacements and removals require the specified original hash. Each modification
has one operation per normalized path, but any number of operations can share
the same hash at different paths. To change a path multiple times, supply
multiple modifications to `withModifications` in order. A subsequent change
must match the hash produced by the preceding change.

`applyTo` checks expectations using an in-memory path index and constructs a new
snapshot. It does not mutate the base, read game files, or alter stored blobs.
Conflicting changes throw `IllegalArgumentException`. Paths must be relative,
remain within the artifact, and describe files rather than a file and its child.
JSON paths use `/` on every platform.

The Gson adapter is registered with `Weaver.GSON`. The separate modification
schema is version 2, with `schema`, `algorithm`, `changes`, `ignore`, and
`removeIgnore` fields. Version 1 modifications remain readable. A file change
has `operation` (`add`, `replace`, or `remove`), an `expected` hash for replace
and remove, and a `replacement` hash for add and replace. Duplicate paths,
duplicate properties, unknown fields, invalid operations, and unsupported
schemas are rejected. JSON object property order does not matter.

## Ignores

```java
ChrysalisModification hidden = ChrysalisModification
    .builder(vanilla.getChrysalis().getBlobFactory().algorithm())
    .ignore(Path.of("cache"))
    .ignore(Path.of("settings/local.json"))
    .build();
ChrysalisizedVault ignored = vanilla.withModifications("ignored", hidden);

ChrysalisModification restoreCache = ChrysalisModification
    .builder(vanilla.getChrysalis().getBlobFactory().algorithm())
    .removeIgnore(Path.of("cache"))
    .build();
ChrysalisizedVault restored = ignored.withModifications("restored", restoreCache);
```

An ignore hides the path itself and all descendants, without filesystem access
or any requirement that the path exists. A directory ignore uses path components:
`cache` covers `cache/nested/file` but not `cache-old`. Ignored files are never
opened during verification or export, and hashes with no active paths are never
scheduled for blob verification. A shared hash is still checked if another copy
remains active. `computeOverBlobs` tasks receive only active path expectations.

`removeIgnore` removes an exact explicit ignore inherited from the input snapshot;
it throws if that root was not present. It does not create a child exception to
an ignored parent. Parent and child roots are retained separately, so removing
one does not remove the other. Adding an already inherited ignore is idempotent.
One layer cannot both ignore and removeIgnore the same root. Ignore operations
are independent of file changes: a layer can replace and ignore the same file.

Full snapshots retain hidden hashes in `blobs()` and expose explicit roots through
`getIgnoredPaths()`. Use `activeBlobs()` for the effective file set and
`isIgnored(relativePath)` to skip a path during a directory scan; prune an ignored
directory before descending into it. An exact directory scanner must skip ignored
unexpected files too. Ignores do not delete files or discard expectations, so
removing an ignore restores checking against the retained (possibly modified)
hash. `between` includes changes to both file expectations and explicit roots.

The modification JSON uses arrays such as `"ignore": ["cache"]` and
`"removeIgnore": ["cache"]`. Resolved snapshots with ignores use Chrysalis
schema 3 (`ignoredPaths`); snapshots without ignores retain schema 2 output.
Older full manifest schemas remain readable. Cloning and serialization preserve
ignore state, including unknown paths and nested roots.

## Cached active expectations

Completed snapshots expose immutable maps through `blobs()` and inherited
`results()`. `activeBlobs()` lazily computes and caches one immutable map; repeated
calls return the same object. With no ignores, it returns the stored map directly.
Concurrent first calls share one safely published result. Internal setters copy
their inputs and invalidate the cache, so callers cannot mutate a supplied map
or an exposed collection and silently leave stale active expectations behind.

Clones share immutable stored expectations and an already computed cache, while
remaining separate snapshot objects. Internal changes to one clone replace its
data and invalidate only its cache. Task-local checking views also reuse the
cached active map. The deprecated `setBlobs` remains available for internal
construction; use a separate mutable multimap while building data, then pass it
to that setter rather than modifying `chrysalis.blobs()` directly.

For complete snapshots, `ChrysalisModification.between(base, modified)` computes
the additions, replacements, and removals automatically. **Do not pass a partial
snapshot containing only modified files as the target:** every omitted base
file becomes a removal. For a directory containing only modified files, use the
builder to replace those particular base paths instead.

To modify a JAR, copy its original blob, edit that copy, hash the complete edited
JAR, and store its bytes under the new hash in the existing shared blob directory.
The modification only references the replacement hash. Original bytes and
unchanged blobs remain shared and available to vanilla installations.

`withModifications` creates an in-memory vault view and preserves the selected
context's effective tags, but does not persist a declaration. `hasChrysalis`
checks whether the loaded source file exists; temporary compositions return false. Named context loading and
saving go through `Vault`, which owns their location and inheritance.

Export into a fresh destination to materialize the resolved file set. Existing
export does not reconcile an installed directory or delete removed paths, and
existing directory verification checks expected files without rejecting extras.
Removal here removes the file expectation; enforcing absence in an existing
installation requires exact directory verification or a separate reconciliation
operation. Changes inside a JAR are verified through the whole JAR's hash.

## Validation

Tests cover independent changes to repeated hashes, 50,000 shared-hash paths,
precondition failures, conflicting paths, snapshot diffs, JSON and file round
trips, malformed JSON, SHA-256, ordered vault views, and fresh-directory export.
Regression tests also cover clone associations and named manifest resolution.
Ignore tests cover missing and corrupt ignored files, missing ignored-only blobs,
shared active hashes, nested roots, restoration, both JSON formats, independent
file and ignore changes, and 50,000 ignored descendants.

## Named vault contexts

The artifact directory contains `blobs.json`, `modded.json`, `strict.json`, and
other named declarations sharing one vault blob store. `chrysalisize(artifact)`
loads `blobs`; `chrysalisize(artifact, name)` loads and resolves that context.
`blobs` is reserved for a full snapshot. Names have no file extension or path
components. Existing untyped full manifests (schemas 0 through 3) remain readable;
Weaver writes both snapshots and modifications using the versioned context
envelope, preserving local tags and ignore roots.

An internal declaration contains either a snapshot or a modification plus local metadata.
The public loading result is always `ChrysalisizedVault`, which binds the resolved
`Chrysalis`, tags, ancestry, artifact, and owning `Vault`.
New context JSON explicitly identifies `type` as `snapshot` or `modification`.
The context envelope has its own `schema: 1`; its `content` object retains the
snapshot or modification's separate schema. Unsupported context versions are
rejected. Previous flat typed contexts remain readable and are upgraded to the
versioned envelope when saved. The Gson adapter is registered with
`Weaver.GSON`. The old `Chrysalis.fromFile` and
`ChrysalisModification.fromFile` helpers are deprecated; use `vault.load(artifact, file)`
for either kind of JSON without knowing its type in advance.

```java
vault.saveModification(artifact, "modded", "blobs", classpathFix,
    Set.of("allow-extra-files", "vendor:custom"), Set.of());
vault.saveModification(artifact, "strict", "modded", strictChanges,
    Set.of("strict"), Set.of("allow-extra-files"));
ChrysalisizedVault strict = vault.chrysalisize(artifact, "strict");
Set<String> effectiveTags = strict.getTags();
Set<String> localTags = strict.getLocalTags();
Set<String> removedTags = strict.getRemovedTags();
Vault owner = strict.getVault();
```

The resulting `strict.json` declaration has this shape:

```json
{
  "schema": 1,
  "type": "modification",
  "parent": "modded",
  "tags": ["strict"],
  "removeTags": ["allow-extra-files"],
  "content": {
    "schema": 2,
    "algorithm": "SHA-1",
    "changes": {},
    "ignore": [],
    "removeIgnore": []
  }
}
```

A missing or null `parent` starts with empty expectations, ignores, and tags,
using the modification's declared hash algorithm. It never implicitly uses
`blobs`. There is only one parent. An iterative loader rejects cycles and missing
parents, resolves file/ignore changes, then computes effective tags as
`(parent tags - removeTags) + tags`. Removing an absent tag is a no-op; adding
and removing the same tag in one declaration is rejected. Tags are case-sensitive,
opaque strings: unknown tags propagate and are preserved for custom consumers.
The built-in verifier does not automatically interpret `strict` or other tags.
Custom consumers can inspect local tag additions, removals, and effective tags
to choose their own behavior.

`loadContext(artifact, name)` and `chrysalisize(artifact, name)` both return a
resolved `ChrysalisizedVault`. `load(artifact, file)` accepts arbitrary JSON
paths. Every parent is resolved by its local name to a sibling JSON file in the
loaded declaration's directory; the entire chain stays in that directory.
Loading an external file does not register or save it as a named context and
never falls back to parents in the owning vault's artifact directory. The supplied
vault still provides blob storage. A same-named context in a different vault or
artifact directory is unrelated.

Cycle detection tracks local filenames in an ordered set and throws
`DatabaseVaultCircularityError` when one repeats. The error lists the traversed
names, including the repeated filename. Validation runs on both load and save;
a rejected save does not replace the previous declaration. A declaration whose
parent has its own name is a self-cycle, even if another directory has a
parentless context with that name. Parent names cannot contain directory
components or drive prefixes.

`saveModification` and `saveSnapshot` validate inheritance and hash preconditions
before writing; all saves return the resolved view. Saves default to refusing
overwrite. Their full overloads accept `replaceExisting` as the final boolean.
Saving a declaration does not produce or verify blob bytes.

`loaded.saveAs("modded")` saves the loaded local declaration into the vault,
preserving its parent reference, operations, and local tags instead of flattening
resolved state. Saving into the vault resolves that parent name in the destination
artifact directory; it never retains a dependency on the external directory. `saveAs(name, true)` explicitly replaces an existing declaration.
Temporary `withModifications` views have no local declaration and cannot use
`saveAs`; persist their resolved state using `saveSnapshot` instead. Standalone
patch `toFile` is deprecated; it omits the parent and tags.

## Ancestry navigation

```java
ChrysalisizedVault modded = vault.chrysalisize(artifact).getChild("modded");
Optional<ChrysalisizedVault> parent = modded.getParent();
ChrysalisizedVault root = modded.getRoot();
```

`getParent()` returns the immediate resolved parent, or an empty optional for a
parentless context. `getRoot()` returns the oldest ancestor, or this instance
when it has no parent; the root need not be `blobs`. Ancestry is captured at load
time, so parent/root navigation does not reread JSON files. Temporary compositions
retain their source view as their parent.

`getChild(name)` loads an existing named context from the same vault and artifact
and requires its immediate declared parent to be this named context. Missing
contexts, unrelated contexts, and grandchildren are rejected. The method does
not create or reparent anything. External files and temporary compositions cannot
serve as named parents until saved. JSON has no persisted children list.

## Fluent weaving of snapshots and modifications

The existing constructors remain convenience defaults for new versions:

```java
ChrysalisizedVault blobs = new Weaver(vault, artifact, completeDirectory).weave();
```

Configure `Weaver` directly before calling `weave()`:

```java
ChrysalisizedVault blobs = new Weaver(vault, artifact, completeDirectory)
    .ignore(Path.of("cache"))
    .tag("strict", "vendor:custom")
    .weave();

ChrysalisizedVault modded = new Weaver(vault, artifact, completeModdedDirectory)
    .name("modded")
    .parent("blobs")
    .ignore(Path.of("logs"))
    .removeIgnore(Path.of("cache"))
    .tag("modded")
    .removeTag("strict")
    .weave();
```

`name`, `parent`, and `force` replace their previous setting; ignore and tag
methods accumulate entries and accept multiple arguments. Repeated entries are
idempotent, and ignore paths are normalized. The methods return the same `Weaver`.
Constructor arguments for name and force remain supported, as does the `IDownload`
constructor. The no-argument `name()` still identifies the wrapped artifact.

No parent, including an explicit `parent(null)`, produces a standalone snapshot,
even if the output name is `modded`. There is no implicit inheritance from `blobs`.
An explicit parent produces a modification. `blobs` is reserved for a standalone
snapshot and can have its own ignores and tags. The former static
`weaveModification` API has been removed; hashing and blob ingestion are instance
operations used by the shared `weave()` workflow.

The directory is the **complete desired file set**, not a directory containing
only changed files. Every omitted parent path becomes a removal, including raw
expectations hidden by ignores. Thousands of changes and repeated content at
many paths are diffed automatically. Files under ignored roots are also hashed
and stored, allowing a later `removeIgnore` to restore their expectations.
Explicit ignore operations are preserved even when an addition is redundant.

Ignore removals require an exact inherited root. A parentless snapshot therefore
cannot remove an ignore. Removing an absent tag is harmless; tag removals without
a parent have no effect and are omitted from the snapshot declaration. Adding
and removing the same ignore root or tag in one layer is rejected. Metadata and
ancestry are validated before storing bytes.

Weaving uses the parent's hashing algorithm, or SHA-1 without a parent. It streams
files, hashes in parallel, deduplicates content, verifies reused and newly copied
blobs, and supports empty directories. JSON is saved only after all required bytes
have been stored successfully; no intermediate manifest is written. Successfully
stored but unreferenced blobs can remain after failure.

Output declarations are not replaced by default. `force(true)` permits replacing
the selected declaration. For standalone snapshots, force also repairs stored
blobs, preserving the behavior of the existing force constructors. For child
modifications, force verifies and reuses shared blobs without overwriting them.
Both outputs use the versioned context JSON envelope; older formats remain readable.

Configuration becomes immutable when `weave()` first starts. Mutators and a second
concurrent weave are rejected. If weaving fails, the same instance may retry with
its frozen configuration; create a new instance to change settings. Successful
weaving leaves its result available through `getChrysalisizedVault()`, and a
second successful weave is rejected as before.

Verification and ingestion use MassHash 2.1.5.1 directly with the declared
algorithm. MassHash supplies fresh digests for concurrent calls and closes hashing
streams, including when digest creation fails.
