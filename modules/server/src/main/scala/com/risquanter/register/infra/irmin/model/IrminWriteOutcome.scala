package com.risquanter.register.infra.irmin.model

import com.risquanter.register.domain.data.iron.CommitHash

/** What a compare-and-set branch write did. A refusal is an answer, not a
  * failure: the caller holds the expected head and decides what the refusal
  * means for its own request.
  *
  * {{{
  * deleteBranch(scenario, expectedHead = abc)   // another tab deleted it first
  * // Refused(None)
  * }}}
  */
enum IrminWriteOutcome:
  case Applied
  /** The branch's head, read just after the refusal. `None` when the branch
    * has no head. */
  case Refused(head: Option[CommitHash])

/** What a merge did. */
enum IrminMergeOutcome:
  case Merged(commit: IrminCommit)
  /** Irmin refused the merge; its own text is diagnostic only. */
  case Conflicted(reason: String)
