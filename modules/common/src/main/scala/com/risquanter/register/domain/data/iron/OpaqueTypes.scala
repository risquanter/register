package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import io.github.iltotore.iron.constraint.string.{Match, ValidURL}

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
  def fromString(s: String): Either[List[String], SafeName] = 
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
  def fromString(s: String): Either[List[String], Email] = 
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
  def fromString(s: String): Either[List[String], Url] = 
    ValidationUtil.refineUrl(s)
