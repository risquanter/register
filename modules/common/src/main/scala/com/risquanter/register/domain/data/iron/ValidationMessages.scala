package com.risquanter.register.domain.data.iron

import com.risquanter.register.domain.errors.{Alphabet, ExpectedInput, FieldProblem, ValueFormat, ValueRange}

/** The sentence for each refusal, shared across JVM and JS.
  *
  * One total function over `FieldProblem`, so every case has a sentence and a
  * sentence names only the values its case carries (ADR-037). The browser calls
  * it where a refusal is shown and a server log line calls it where one is
  * recorded; nothing on the wire is a sentence.
  */
object ValidationMessages:

  def render(problem: FieldProblem): String = problem match
    // Presence and readability
    case FieldProblem.Required                 => "A value is required"
    case FieldProblem.Unreadable(expected)     => unreadable(expected)
    case FieldProblem.DeclaredConstraintNotMet => "The value is not valid"

    // The shape of one value
    case FieldProblem.TooLong(limit)                => s"Value must be at most $limit characters"
    case FieldProblem.LengthOutOfRange(min, max)    => s"Value must be between $min and $max characters"
    case FieldProblem.InvalidCharacters(allowed)    => s"Value must use only ${alphabet(allowed)}"
    case FieldProblem.WrongFormat(expected)         => format(expected)
    case FieldProblem.OutOfRange(range)             => outOfRange(range)
    case FieldProblem.ScenarioNameReserved          => "'main' is reserved and cannot be used as a scenario name"

    // Names and seed identity within a tree
    case FieldProblem.ReservedNodeNames(names) =>
      s"Node name must not be a reserved query symbol (e.g. 'leaf', 'p95'): ${names.map(_.value).mkString(", ")}"
    case FieldProblem.DuplicateNodeName(name, occurrences) =>
      s"duplicate node name: ${name.value} — used by $occurrences nodes; names must be unique within a tree. " +
      "Rename all but one of them."
    case FieldProblem.DuplicateMitigationName(name, occurrences) =>
      s"duplicate mitigation name: ${name.value} — used by $occurrences mitigations; names must be unique within a tree. " +
      "Rename all but one of them."
    case FieldProblem.DuplicateMitigationId(id, occurrences) =>
      s"duplicate mitigation id: ${id.value} — carried by $occurrences mitigations; identifiers must be unique within a tree. " +
      "Remove all but one of them, or give each its own identifier."
    case FieldProblem.SeedVarIdInUse(seedVarId, holders) =>
      s"seedVarId ${seedVarId.value} is used by multiple nodes: ${holders.map(_.value).mkString(", ")}. Only one may keep it. " +
      "A seedVarId cannot be changed once it is assigned, so change or omit the one you supplied."
    case FieldProblem.SeedVarIdImmutable =>
      "seedVarId is immutable once assigned — omit it for existing nodes; " +
      "to change a risk's stream, delete the leaf and recreate it"
    case FieldProblem.SeedVarIdExhausted(next) =>
      s"seedVarId assignment space exhausted: next value $next exceeds the valid range"
    case FieldProblem.SeedVarHighWaterBelowMax(highWater, maxAssigned) =>
      s"seedVarHighWater ${highWater.value} is below the highest assigned seedVarId ${maxAssigned.value}"
    case FieldProblem.SeedVarHighWaterNotDerivable =>
      "tree has no leaves — seedVarHighWater cannot be derived"

    // Topology of a submitted tree, referenced by name
    case FieldProblem.RootRequired              => "Exactly one root is required"
    case FieldProblem.MultipleRoots             => "Multiple roots found"
    case FieldProblem.RootIdNotFound(rootId)    => s"rootId '${rootId.value}' not found in nodes"
    case FieldProblem.ParentPortfolioRequired   => "leaf must have a parent portfolio"
    case FieldProblem.ParentNotFound(parent)    => s"parentName '${parent.value}' not found in portfolios"
    case FieldProblem.ParentIsLeaf(parent)      => s"parentName '${parent.value}' refers to a leaf; must reference a portfolio"
    case FieldProblem.CycleDetected(node)       => s"Cycle detected at node '${node.value}'"
    case FieldProblem.PortfolioHasNoChildren(Nil) =>
      "Portfolio must have at least one child"
    case FieldProblem.PortfolioHasNoChildren(portfolios) =>
      s"Every portfolio must have at least one child: ${portfolios.map(_.value).mkString(", ")}"
    case FieldProblem.TooManyChildren(count, limit)    => s"too many children: $count exceeds the limit of $limit"
    case FieldProblem.TooManyNodes(count, limit)       => s"too many nodes: $count exceeds the limit of $limit"
    case FieldProblem.TooManyMitigations(count, limit) => s"too many mitigations: $count exceeds the limit of $limit"

    // A stored node named by a tree update
    case FieldProblem.StoredNodeListedTwice => "this id is listed more than once; each stored node appears once, in portfolios or in leaves"
    case FieldProblem.StoredNodeIsLeaf      => "this id is a leaf of this tree; it belongs in leaves"
    case FieldProblem.StoredNodeIsPortfolio => "this id is a portfolio of this tree; it belongs in portfolios"
    case FieldProblem.StoredNodeNotInTree   => "this id is not a node of this tree; a new node goes in newPortfolios or newLeaves"

    // The index of a built tree
    case FieldProblem.ChildParentMismatch(child, declaredParent) =>
      s"Node '${child.value}' is listed as a child but has parentId=${declaredParent.fold("None")(_.value)}"
    case FieldProblem.ChildNotFound(child)             => s"Child '${child.value}' does not exist in nodes"
    case FieldProblem.ParentDoesNotListChild(parent)   => s"parent '${parent.value}' does not list this node as a child"
    case FieldProblem.ParentIdIsLeaf(parent)           => s"parent '${parent.value}' is a leaf, not a portfolio"
    case FieldProblem.ParentIdNotFound(parent)         => s"parent '${parent.value}' does not exist in nodes"
    case FieldProblem.NodeNotInIndex                   => "Node not found in tree index"
    case FieldProblem.LeafUnreachable                  => "Leaf not reachable from tree root (no content hash)"

    // Loss distribution
    case FieldProblem.AnchorCountOutOfRange(count, min, max) =>
      s"Metalog mode takes between $min and $max percentile-quantile pairs (got $count)"
    case FieldProblem.PercentileQuantileLengthMismatch(percentiles, quantiles) =>
      s"Percentiles and quantiles must have same length ($percentiles vs $quantiles)"
    case FieldProblem.PercentilesNotIncreasing =>
      "Percentiles must be strictly increasing — values must be in ascending order"
    case FieldProblem.QuantilesNotIncreasing =>
      "Quantiles must be strictly increasing — a higher percentile must correspond to a higher loss value"
    case FieldProblem.TermsAboveAnchorCount =>
      "Terms must be between 2 and the number of percentile-quantile pairs"
    case FieldProblem.LossRangeNotOrdered(min, max) =>
      s"Minimum loss ($min) must be less than maximum loss ($max)"
    case FieldProblem.MetalogFitFailed =>
      "The inputs could not be fitted to a valid distribution. " +
      "Try reducing Terms, check that quantile values are strictly increasing, " +
      "or remove conflicting estimates."
    case FieldProblem.LognormalFitFailed =>
      "The bounds could not be fitted to a lognormal distribution. " +
      "Check that the minimum loss is above zero and below the maximum, " +
      "and that the two are far enough apart to describe a spread."

    // Mitigation
    case FieldProblem.PipelineEmpty(min) =>
      s"result-stage pipeline is empty: at least $min step is required"
    case FieldProblem.PipelineTooLong(count, limit) =>
      s"result-stage pipeline has too many steps: $count exceeds the limit of $limit"
    case FieldProblem.CapMustExceedDeductible => "Cap must be greater than deductible"
    case FieldProblem.AggregatedLossOverflow  => "Aggregated loss exceeds the maximum representable value"
    case FieldProblem.MitigationEliminatesRisk =>
      "This mitigation removes every loss from the risk, leaving a residual of exactly zero — a mitigation reduces exposure and cannot assert that a risk has ceased to exist. Raise the cap or the scale factor, lower the deductible, or lower the threshold; a risk that genuinely no longer exists is removed from the tree instead."
    case FieldProblem.MitigationLowersMinLossBelowFloor(min, minFactor) =>
      f"This mitigation reduces the minimum loss below 1, the smallest representable whole unit. The leaf's minimum loss is $min, so the smallest severity factor that still applies here is $minFactor%.3e (1 / $min)."
    case FieldProblem.MitigationLowersAnchorLossBelowFloor(min, minFactor) =>
      f"This mitigation reduces the smallest anchor loss to zero, below the positive floor an anchor loss must stay above. The leaf's smallest anchor loss is $min%.3e, so the smallest severity factor that still applies here is $minFactor%.3e."
    case FieldProblem.TargetingPredicateUnparseable  => "Targeting predicate is not valid syntax"
    case FieldProblem.TargetingQuantifierNotAllowed  => "Targeting predicates may not use quantifiers"
    case FieldProblem.TargetingFunctionNotAllowed    => "Targeting predicates may not use function application"
    case FieldProblem.TargetingQuantifierDepthExceeded(limit, found) =>
      s"Targeting predicate quantifier depth $found exceeds the limit of $limit"
    case FieldProblem.TargetingNoFreeVariable =>
      "Targeting predicate must reference exactly one variable; found none"
    case FieldProblem.TargetingSeveralFreeVariables =>
      "Targeting predicate must have exactly one free variable. " +
      "Unquoted words are read as variables — if one was meant as a literal value, quote it (e.g. \"cyber\")."
    case FieldProblem.TargetingMitigationStateReferenced =>
      "Targeting predicates may not reference mitigation state (mitigate, mitigated, unmitigated)"

    // Workspace limits and lookups
    case FieldProblem.SeedEntityIdInUse(seedEntityId) =>
      s"seedEntityId ${seedEntityId.value} is already used by another workspace — choose another value or omit to auto-assign"
    case FieldProblem.MaxTreesReached(limit) =>
      s"workspace already holds the maximum of $limit risk trees — delete one before creating another"
    case FieldProblem.NodeNotFound                 => "Node not found in tree"
    case FieldProblem.NodeIdsEmpty                 => "nodeIds set is empty"
    case FieldProblem.TreeIdAlreadyExists(treeId)  => s"Tree ID '${treeId.value}' already exists"
    case FieldProblem.TreeNameAlreadyExists(name)  => s"Tree name '${name.value}' already exists"
    case FieldProblem.ScenarioNotFound(scenario)   => s"Scenario not found: ${scenario.value}"
    case FieldProblem.CommitNotFound(commit)       => s"Commit not found: ${commit.value}"
    case FieldProblem.WorkspaceHasNoContent        => "Workspace has no content yet — nothing to fork a scenario from"
    case FieldProblem.RequiresIrminBackend         => "This read requires the Irmin backend"
    case FieldProblem.Unspecified                  => "Unknown validation error"

  private def unreadable(expected: ExpectedInput): String = expected match
    case ExpectedInput.Number      => "Value must be a number"
    case ExpectedInput.WholeNumber => "Value must be a whole number"
    case ExpectedInput.NumberList  => "Enter comma-separated numbers"
    case ExpectedInput.SingleValue => "A single value is expected"
    case ExpectedInput.Declared    => "The value could not be read"

  private def alphabet(allowed: Alphabet): String = allowed match
    case Alphabet.NodeName     => "letters, digits, spaces, hyphens, and forward slashes"
    case Alphabet.ScenarioName => "letters, digits, spaces, hyphens, and underscores"

  /** The refused value is deliberately absent from every format sentence. It is
    * client-supplied text, and a sentence reaches a client inside a refusal, so
    * it is a disclosure surface whether or not the value came from the same
    * client (ADR-035). The ULID's 26 characters and alphabet are fixed by the
    * ULID specification; the hash lengths by SHA-1 and SHA-256; the trace id by
    * the W3C Trace Context specification. */
  private def format(expected: ValueFormat): String = expected match
    case ValueFormat.Ulid =>
      "Invalid ID (expected a 26-character ULID in Crockford base32: digits and uppercase letters, excluding I, L, O and U)"
    case ValueFormat.WorkspaceKey         => "Invalid workspace key (expected 22-character base64url token)"
    case ValueFormat.WorkspaceKeyHash     => "Invalid workspace key hash (expected 64-character lowercase hex SHA-256 digest)"
    case ValueFormat.UserId               => "User ID must be a valid UUID (e.g. 8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf)"
    case ValueFormat.Email                => "Please enter a valid email address"
    case ValueFormat.Url                  => "Please enter a valid URL (e.g. https://example.com)"
    case ValueFormat.SecureUrl            => "URL must be a valid HTTPS endpoint (http:// is not permitted for external service credentials)"
    case ValueFormat.ExternalToken        => "Token must be non-empty, at most 2048 characters, and contain only printable ASCII (0x21–0x7E)"
    case ValueFormat.TraceId              => "Trace id is invalid (expected 32 lowercase hexadecimal characters)"
    case ValueFormat.CommitHash           => "Commit hash is invalid (expected 40 lowercase hexadecimal characters)"
    case ValueFormat.ContentHash          => "Content hash is invalid (expected 64 lowercase hexadecimal characters)"
    case ValueFormat.BranchName           => "Branch name is invalid"
    case ValueFormat.StagingBranchName    => "Merge staging branch name is invalid"
    case ValueFormat.DistributionTypeName => "Distribution type must be 'Metalog' or 'Lognormal'"
    case ValueFormat.ScenarioNameStart    => "Scenario name must start with a letter or digit"

  /** Each bound is the one the matching refinement in `ValidationUtil` enforces.
    * The seed bounds are fixed by the HDR generator's 8-decimal-digit id budget,
    * and the metalog term bounds by the fitter's own precondition. */
  private def outOfRange(range: ValueRange): String = range match
    case ValueRange.NonNegative        => "Value must be zero or greater"
    case ValueRange.Positive           => "Value must be greater than zero"
    case ValueRange.SeedEntityId       => "seedEntityId must be between 1 and 99999999 (0 is reserved)"
    case ValueRange.SeedVarId          => "seedVarId must be between 1 and 49999999"
    case ValueRange.OpenUnitInterval   => "Probability must be between 0 and 1 (exclusive)"
    case ValueRange.ClosedUnitInterval => "Occurrence probability must be between 0 and 1 (inclusive)"
    case ValueRange.Percent            => "Probability must be between 0 and 100 (inclusive)"
    case ValueRange.Percentile         => "Percentiles must be between 0 and 100 (exclusive)"
    case ValueRange.PositiveLoss       => "Quantile loss amounts must be greater than zero — the distribution fit takes their logarithm"
    case ValueRange.LossCap =>
      "Loss cap must be greater than 0 — a cap of zero would leave the entity bearing nothing, which states that the transfer cannot fail"
    case ValueRange.MetalogTerms =>
      "Terms must be between 2 and 20 — a fit with fewer has no curve to describe, and the fitter accepts no more"
    case ValueRange.RetentionFactor =>
      "Mitigation factor must be greater than 0 and at most 1 — a mitigation reduces a figure, so it can neither increase it nor remove it entirely"
    case ValueRange.ResidualProbability =>
      "Mitigated probability must be greater than 0 and at most 1 — a mitigation reduces the chance a risk occurs, so it cannot remove the possibility entirely"
