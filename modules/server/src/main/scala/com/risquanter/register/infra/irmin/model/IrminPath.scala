package com.risquanter.register.infra.irmin.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import _root_.zio.json.*
import _root_.zio.prelude.NonEmptyList
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.domain.errors.{ErrorLocation, FieldProblem, ValidationError, ValueFormat}

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

/** One segment of an Irmin path: the text between two separators. Irmin
  * lists a prefix's children as segments, and a path is built by appending
  * them, so a segment that held a separator would add a level instead of
  * being refused. */
type IrminSegmentConstraint = Match["^[a-zA-Z0-9_-]+$"]
type IrminSegmentStr = String :| IrminSegmentConstraint

/** Opaque wrapper for validated Irmin paths */
opaque type IrminPath = IrminPathStr

/** Opaque wrapper for one validated path segment */
opaque type IrminSegment = IrminSegmentStr

object IrminSegment:
  def apply(s: IrminSegmentStr): IrminSegment = s

  /** A name Irmin listed, refined where it arrives (ADR-039). A refused name
    * is the one refusal it states; the name itself is the caller's to log. */
  def from(s: String): Either[NonEmptyList[ValidationError], IrminSegment] =
    s.refineEither[IrminSegmentConstraint]
      .left.map(_ => NonEmptyList(ValidationError(ErrorLocation.Whole, FieldProblem.WrongFormat(ValueFormat.StoreSegment))))

  /** Total: a ULID's characters, [0-9A-HJKMNP-TV-Z], are segment characters. */
  def of(id: SafeId.SafeId): IrminSegment =
    id.value.toString.assume[IrminSegmentConstraint]

  extension (s: IrminSegment)
    def value: String = s

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

    /** Append a segment to this path. Total: a valid path joined to a valid
      * segment by one separator is a valid path. */
    def /(segment: IrminSegment): IrminPath =
      val s: String = IrminSegment.value(segment)
      val joined: String = if p.isEmpty then s else s"$p/$s"
      joined.assume[IrminPathConstraint]

    /** Get parent path (None if root). Everything before the last separator
      * of a valid path is a valid path. */
    def parent: Option[IrminPath] =
      val lastSlash = p.lastIndexOf('/')
      if lastSlash < 0 then
        if p.isEmpty then None else Some(IrminPath.root)
      else
        Some(p.substring(0, lastSlash).assume[IrminPathConstraint])

    /** Get last segment (None if root). Every segment of a valid path is a
      * valid segment. */
    def name: Option[IrminSegment] =
      val lastSlash = p.lastIndexOf('/')
      if lastSlash < 0 then
        if p.isEmpty then None else Some((p: String).assume[IrminSegmentConstraint])
      else
        Some(p.substring(lastSlash + 1).assume[IrminSegmentConstraint])

    /** Split path into segments */
    def segments: List[IrminSegment] =
      if p.isEmpty then Nil else p.split('/').toList.map(_.assume[IrminSegmentConstraint])

  // JSON codecs
  given JsonEncoder[IrminPath] = JsonEncoder.string.contramap(_.value)
  given JsonDecoder[IrminPath] = JsonDecoder.string.mapOrFail(s =>
    IrminPath.from(s)
  )
