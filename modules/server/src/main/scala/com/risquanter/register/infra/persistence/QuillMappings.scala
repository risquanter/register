package com.risquanter.register.infra.persistence

import io.getquill.MappedEncoding
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash}

/** How the refined identifiers this service writes reach a column. Reading goes
  * the other way through the row types' text columns, so a stored value that
  * does not refine is a refusal the reader accumulates rather than an exception
  * thrown inside the mapping. */
object QuillMappings:
  given MappedEncoding[WorkspaceId, String] = MappedEncoding(_.value)

  given MappedEncoding[TreeId, String] = MappedEncoding(_.value)

  given MappedEncoding[WorkspaceKeyHash, String] = MappedEncoding(_.value)
