package com.risquanter.register.infra.persistence

import io.getquill.MappedEncoding
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash}
import com.risquanter.register.domain.errors.ValidationError

object QuillMappings:
  /** A stored column that does not refine is a defect of what this service
    * wrote, so the mapping throws rather than reporting a refusal. */
  private def corrupt(errs: List[ValidationError]): Nothing =
    throw new IllegalArgumentException(errs.map(ValidationError.describe).mkString("; "))

  given MappedEncoding[WorkspaceId, String] = MappedEncoding(_.value)
  given MappedEncoding[String, WorkspaceId] = MappedEncoding(value =>
    WorkspaceId.fromString(value).fold(corrupt, identity)
  )

  given MappedEncoding[TreeId, String] = MappedEncoding(_.value)
  given MappedEncoding[String, TreeId] = MappedEncoding(value =>
    TreeId.fromString(value).fold(corrupt, identity)
  )

  given MappedEncoding[WorkspaceKeyHash, String] = MappedEncoding(_.value)
  given MappedEncoding[String, WorkspaceKeyHash] = MappedEncoding(value =>
    WorkspaceKeyHash.fromString(value).fold(corrupt, identity)
  )
