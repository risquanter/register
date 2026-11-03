# NOTE — Irmin GraphQL `merge_with_commit`: ancestry of the merged result

**Status:** engineering note, not an ADR. It records what Irmin 3.11's merge
mutations do to ancestry, so that an Irmin bump or any new merge use starts
from the source rather than from observation.

The scenario merge calls `merge_with_commit` (`IrminClient.mergeCommit`),
merging the scenario commit the conflict pre-check compared into a staging
branch. The local patch to `irmin-graphql` (`containers/builders/patches/`,
ADR-020 §11) prefixes that mutation's conflict error with
`"merge conflict: "`.

## What the merge does

Both GraphQL merge mutations end in the same store operation. In Irmin
3.11.0, `irmin/store.ml` (lines 1053–1065) defines `merge_with_branch` as
"look up the branch's head commit, then `Head.merge`", and `merge_with_commit`
as `Head.merge` on the given commit directly. They differ only in how they find
the source commit, in the text of the conflict error, and in how a missing
source fails: an unknown branch name raises `invalid_arg`, and an unknown hash
answers `"invalid hash"`.

`Head.merge` reaches `Commit.merge_commit` (`irmin/commit.ml`, lines
179–213). It computes the common ancestor of the target's head and the source
commit, then:

- if that ancestor is the target's head, it fast-forwards: the target's new
  head is the source commit itself;
- if that ancestor is the source commit, the source is already merged and the
  target's head stays as it is;
- otherwise it merges the two trees and writes a new commit whose parents are
  both heads;
- on a conflict it returns an error and the target's head is unchanged.

So no path through the merge writes a commit that drops the source commit
from its ancestry.

## How it is pinned

`IrminMergeSemanticsSpec` runs every case through `mergeCommit`:

- a merge into an unmoved target returns the source commit's own hash;
- the same holds after a refused merge has run on the store;
- a merge of diverged branches records both heads as parents;
- a conflicting merge fails as `IrminMergeConflict` and leaves the target's
  head where it was.

## Re-checking after an Irmin bump

Read `Store.merge_with_commit`, `Head.merge` and `Commit.merge_commit` in the
new source and confirm the three outcomes above still hold. Then run
`sbt "serverIt/testOnly *IrminMergeSemanticsSpec"` against the rebuilt image.
