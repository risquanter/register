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

/**
 * Common constant values for Iron refined types.
 * 
 * Use these instead of `1.refineUnsafe[Greater[0]]` scattered throughout the codebase.
 * Import with: `import com.risquanter.register.domain.data.iron.IronConstants.*`
 * 
 * These are compile-time safe since Iron validates literal values at compile time.
 */
object IronConstants:
  // PositiveInt constants (Int > 0)
  val One: PositiveInt = 1
  val Two: PositiveInt = 2
  val Four: PositiveInt = 4
  val Ten: PositiveInt = 10
  val Hundred: PositiveInt = 100
  val Thousand: PositiveInt = 1000
  val TenThousand: PositiveInt = 10000
  
  // NonNegativeInt constants (Int >= 0)
  val Zero: NonNegativeInt = 0
  val NNOne: NonNegativeInt = 1   // "NN" prefix = NonNegative (avoids clash with One)
  val NNFive: NonNegativeInt = 5
  val NNTen: NonNegativeInt = 10
  
  // NonNegativeLong constants (Long >= 0)
  val ZeroL: NonNegativeLong = 0L
  val OneL: NonNegativeLong = 1L

// Opaque type for Metalog/HDR PRNG counters (zero-cost abstraction)
// Used for the 4-5 Long counter parameters in HDR.generate(counter, entityId, varId, seed3, seed4)
// This signals that these Longs are semantically distinct from regular numeric values—
// they're stream identifiers for deterministic random number generation, not quantities or IDs.
// No validation needed; any Long is valid, but the type prevents accidental mixing.
object PRNGCounter:
  opaque type PRNGCounter = Long
  
  object PRNGCounter:
    /** Create counter from any Long value */
    def apply(value: Long): PRNGCounter = value
    def unapply(counter: PRNGCounter): Option[Long] = Some(counter)
    
  extension (counter: PRNGCounter)
    /** Extract underlying Long value */
    def value: Long = counter

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
