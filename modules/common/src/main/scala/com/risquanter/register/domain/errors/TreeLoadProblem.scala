package com.risquanter.register.domain.errors

/** Why one tree in a workspace listing could not be loaded. */
enum TreeLoadProblem:
  /** The listing named the tree, and the commit holds neither its metadata
    * nor any node under it. */
  case Empty

  /** Reading or rebuilding the tree failed. The failure is kept whole, so a
    * reader classifies it by its case rather than by its text. */
  case Failed(cause: AppError)

object TreeLoadProblem:
  /** The problem as diagnostic text, for a log line. */
  def describe(problem: TreeLoadProblem): String = problem match
    case Empty         => "neither metadata nor nodes are stored"
    case Failed(cause) => cause.logMessage
