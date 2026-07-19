package com.risquanter.register.infra.irmin.model

/**
  * One entry of a `set_tree` mutation (DD-7).
  *
  * Corresponds to the GraphQL `TreeItem` input type:
  * ```graphql
  * input TreeItem {
  *   path: Path!
  *   value: Value
  *   metadata: Metadata
  * }
  * ```
  *
  * @param path Path RELATIVE to the mutation's `path` argument (probe-verified,
  *             milestone-2b A9 fact 6) — e.g. "meta" or "nodes/n1", never the
  *             absolute store path
  * @param value JSON string value to store at that path
  */
final case class IrminTreeEntry(
    path: IrminPath,
    value: String
)
