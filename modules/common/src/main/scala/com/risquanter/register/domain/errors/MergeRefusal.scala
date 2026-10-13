package com.risquanter.register.domain.errors

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.RiskTree.{safeNameEncoder, safeNameDecoder}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId}
import com.risquanter.register.http.responses.MergeConflictEntry

/** Why a merge was refused, as a closed set rather than a composed sentence.
  *
  * A refusal is actionable: the remedy differs per case, so the browser needs to
  * know which case it is and the values that go with it. Free text could carry
  * an internal path or a repository message, and a case drawn from a closed set
  * cannot (ADR-035). The same value serves the server's log line, which renders
  * it through `MergeConflict.getMessage`.
  */
@jsonDiscriminator("kind")
enum MergeRefusal:
  /** Paths changed on both branches. Each entry is the same wire form the merge
    * preview reports, so a preview and a refusal name a conflict identically.
    */
  case Conflicts(paths: List[MergeConflictEntry])

  /** The merged node set would repeat a name within a tree, listed per tree. */
  case DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])

  /** The merge was assembled on a staging branch, a tree then failed to load
    * from the merged state, and the merge was discarded — main is untouched and
    * the trees are as they were. Names the trees that failed to load.
    *
    * Irmin merges one storage path at a time and byte-for-byte, so it cannot see
    * an invariant that is a property of the whole node set; this is where those
    * invariants are checked instead.
    */
  case InvariantBroken(trees: List[TreeId])

  /** The store refused the merge itself: main gained a conflicting write between
    * the conflict scan and the merge. It names no paths, because the store's own
    * conflict error names none.
    */
  case BranchesConflict()

object MergeRefusal:
  given codec: JsonCodec[MergeRefusal] = DeriveJsonCodec.gen[MergeRefusal]

  /** One line naming the refusal and its values, for a server log line.
    *
    * The sentence a user reads is composed in the browser (the wire carries the
    * case, not prose), so this exists for the log and nowhere else.
    */
  def describe(refusal: MergeRefusal): String = refusal match
    case Conflicts(paths) =>
      s"${paths.size} conflicting path(s): ${paths.map(_.path).mkString(", ")}"
    case DuplicateNames(perTree) =>
      "duplicate node name(s) — " + perTree.toList
        .sortBy((treeId, _) => treeId.value)
        .map((treeId, names) => s"tree ${treeId.value}: ${names.map(_.value.toString).sorted.mkString(", ")}")
        .mkString("; ")
    case InvariantBroken(trees) =>
      s"the merged state breaks a tree invariant in ${trees.size} tree(s) " +
      s"(${trees.map(_.value).sorted.mkString(", ")}), so the merge was not applied"
    case BranchesConflict() =>
      "the branches conflict — re-run the preview and retry"
