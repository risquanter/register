package com.risquanter.register.infra.irmin.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import _root_.zio.json.*

/**
  * Irmin path type - forward-slash separated path segments.
  *
  * Irmin uses paths like "risks/cyber/severity" to navigate the content-addressed tree.
  * Empty path represents root. Segments are separated by "/".
  *
  * Valid examples:
  * - "" (root)
  * - "risks"
  * - "risks/cyber/severity"
  *
  * Invalid examples:
  * - "/leading-slash"
  * - "trailing-slash/"
  * - "double//slash"
  */
type IrminPathConstraint = Match["^$|^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$"]
type IrminPathStr = String :| IrminPathConstraint

/** Opaque wrapper for validated Irmin paths */
opaque type IrminPath = IrminPathStr

object IrminPath:
  /** Create path from validated string */
  def apply(s: IrminPathStr): IrminPath = s
  
  /** Unsafe creation - use only in tests or with known-valid literals */
  def unsafeFrom(s: String): IrminPath = s.refineUnsafe[IrminPathConstraint]
  
  /** Root path (empty string) */
  val root: IrminPath = "".refineUnsafe[IrminPathConstraint]
  
  /** Safe creation with validation */
  def from(s: String): Either[String, IrminPath] =
    s.refineEither[IrminPathConstraint]
      .left.map(_ => s"Invalid Irmin path: '$s'")

  extension (p: IrminPath)
    /** Get underlying string value */
    def value: String = p
    
    /** Append a segment to this path */
    def /(segment: String): Either[String, IrminPath] =
      val newPath = if p.isEmpty then segment else s"$p/$segment"
      IrminPath.from(newPath)
    
    /** Get parent path (None if root) */
    def parent: Option[IrminPath] =
      val lastSlash = p.lastIndexOf('/')
      if lastSlash < 0 then
        if p.isEmpty then None else Some(IrminPath.root)
      else
        Some(IrminPath.unsafeFrom(p.substring(0, lastSlash)))
    
    /** Get last segment (None if root) */
    def name: Option[String] =
      val lastSlash = p.lastIndexOf('/')
      if lastSlash < 0 then
        if p.isEmpty then None else Some(p)
      else
        Some(p.substring(lastSlash + 1))
    
    /** Split path into segments */
    def segments: List[String] =
      if p.isEmpty then Nil else p.split('/').toList

  // JSON codecs
  given JsonEncoder[IrminPath] = JsonEncoder.string.contramap(_.value)
  given JsonDecoder[IrminPath] = JsonDecoder.string.mapOrFail(s =>
    IrminPath.from(s)
  )
