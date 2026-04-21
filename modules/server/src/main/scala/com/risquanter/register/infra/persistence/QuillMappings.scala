package com.risquanter.register.infra.persistence

import io.getquill.MappedEncoding
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash}

object QuillMappings:
  given MappedEncoding[WorkspaceId, String] = MappedEncoding(_.value)
  given MappedEncoding[String, WorkspaceId] = MappedEncoding(value =>
    WorkspaceId.fromString(value).fold(
      errs => throw new IllegalArgumentException(errs.map(_.message).mkString("; ")),
      identity
    )
  )

  given MappedEncoding[TreeId, String] = MappedEncoding(_.value)
  given MappedEncoding[String, TreeId] = MappedEncoding(value =>
    TreeId.fromString(value).fold(
      errs => throw new IllegalArgumentException(errs.map(_.message).mkString("; ")),
      identity
    )
  )

  given MappedEncoding[WorkspaceKeyHash, String] = MappedEncoding(_.value)
  given MappedEncoding[String, WorkspaceKeyHash] = MappedEncoding(value =>
    WorkspaceKeyHash.fromString(value).fold(
      errs => throw new IllegalArgumentException(errs.map(_.message).mkString("; ")),
      identity
    )
  )
