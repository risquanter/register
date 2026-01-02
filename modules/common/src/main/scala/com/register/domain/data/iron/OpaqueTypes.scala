package com.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.MaxLength

// Base refined type alias used for most short strings:
type SafeShortStr = String :| (Not[Blank] & MaxLength[50])

// Extra short strings (e.g., for tags, codes)
type SafeExtraShortStr = String :| (Not[Blank] & MaxLength[20])

// Non-negative long values (IDs, counts, amounts)
type NonNegativeLong = Long :| GreaterEqual[0L]

// Probability values (must be between 0.0 and 1.0, exclusive)
type Probability = Double :| (Greater[0.0] & Less[1.0])

// Opaque type for names - prevents mixing with other string types
object SafeName:
  opaque type SafeName = SafeShortStr
  
  object SafeName:
    def apply(s: SafeShortStr): SafeName = s
    // Extractor for pattern matching:
    def unapply(sn: SafeName): Option[SafeShortStr] = Some(sn)
    
  extension (sn: SafeName) 
    def value: SafeShortStr = sn

// Opaque type for emails
object Email:
  opaque type Email = SafeShortStr
  
  object Email:
    def apply(s: SafeShortStr): Email = s
    def unapply(e: Email): Option[SafeShortStr] = Some(e)
    
  extension (e: Email)
    def value: SafeShortStr = e

// Opaque type for URLs
object Url:
  opaque type Url = SafeShortStr
  
  object Url:
    def apply(s: SafeShortStr): Url = s
    def unapply(u: Url): Option[SafeShortStr] = Some(u)
    
  extension (u: Url)
    def value: SafeShortStr = u
