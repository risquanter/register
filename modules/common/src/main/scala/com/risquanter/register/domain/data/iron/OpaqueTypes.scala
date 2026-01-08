package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.string.{Match, ValidURL}
import com.risquanter.register.domain.errors.ValidationError

// Base refined type alias used for most short strings:
type SafeShortStr = String :| (Not[Blank] & MaxLength[50])

// Extra short strings (e.g., for tags, codes)
type SafeExtraShortStr = String :| (Not[Blank] & MaxLength[20])

// Email with format validation (single @, max 50 chars)
type ValidEmail = String :| (Not[Blank] & MaxLength[50] & Match["[^@]+@[^@]+"])

// URL with Iron's built-in ValidURL constraint (max 200 chars for longer URLs)
type ValidUrl = String :| (Not[Blank] & MaxLength[200] & ValidURL)

// Non-negative long values (IDs, counts, amounts)
type NonNegativeLong = Long :| GreaterEqual[0L]

// Positive integers (must be > 0)
type PositiveInt = Int :| Greater[0]

// Non-negative integers (>= 0)
type NonNegativeInt = Int :| GreaterEqual[0]

// Probability values (must be between 0.0 and 1.0, exclusive)
// Exclusive bounds are required for numerical stability in simulation-util's
// inverse CDF calculations where 0.0 and 1.0 would cause division by zero or infinity.
// NOTE: Used for both occurrence probabilities AND Metalog percentiles, since QPFitter
// requires exclusive (0,1) bounds: "p must be in (0,1)"
type Probability = Double :| (Greater[0.0] & Less[1.0])

// Distribution type string (must be "expert" or "lognormal")
type DistributionType = String :| Match["^(expert|lognormal)$"]

// Opaque type for names - prevents mixing with other string types
object SafeName:
  opaque type SafeName = SafeShortStr
  
  object SafeName:
    def apply(s: SafeShortStr): SafeName = s
    // Extractor for pattern matching:
    def unapply(sn: SafeName): Option[SafeShortStr] = Some(sn)
    
  extension (sn: SafeName) 
    def value: SafeShortStr = sn
  
  // Convenience constructor from plain String
  def fromString(s: String): Either[List[ValidationError], SafeName] = 
    ValidationUtil.refineName(s)

// Opaque type for emails
object Email:
  opaque type Email = ValidEmail
  
  object Email:
    def apply(s: ValidEmail): Email = s
    def unapply(e: Email): Option[ValidEmail] = Some(e)
    
  extension (e: Email)
    def value: ValidEmail = e
  
  // Convenience constructor from plain String
  def fromString(s: String): Either[List[ValidationError], Email] = 
    ValidationUtil.refineEmail(s)

// Opaque type for URLs
object Url:
  opaque type Url = ValidUrl
  
  object Url:
    def apply(s: ValidUrl): Url = s
    def unapply(u: Url): Option[ValidUrl] = Some(u)
    
  extension (u: Url)
    def value: ValidUrl = u
  
  // Convenience constructor from plain String
  def fromString(s: String): Either[List[ValidationError], Url] = 
    ValidationUtil.refineUrl(s)

// SafeId: Alphanumeric + hyphen/underscore, 3-30 chars (risk/portfolio identifiers)
// Valid examples: "cyber-attack", "ops_risk_001", "IT-RISK"
type SafeIdStr = String :| (Not[Blank] & MinLength[3] & MaxLength[30] & Match["^[a-zA-Z0-9_-]+$"])

// Opaque type for risk/portfolio IDs
object SafeId:
  opaque type SafeId = SafeIdStr
  
  object SafeId:
    def apply(s: SafeIdStr): SafeId = s
    def unapply(id: SafeId): Option[SafeIdStr] = Some(id)
    
  extension (id: SafeId)
    def value: SafeIdStr = id
  
  // Convenience constructor from plain String
  def fromString(s: String): Either[List[ValidationError], SafeId] =
    ValidationUtil.refineId(s)
