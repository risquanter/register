package com.risquanter.register.common

/** 
 * Compile-time constants for the Risk Register system.
 * 
 * These values are NOT configurable at runtime - they define
 * fundamental constraints that must match Iron refinement types.
 */
object Constants {
  // ══════════════════════════════════════════════════════════════════
  // Validation Constraints (must match Iron refinement types)
  // ══════════════════════════════════════════════════════════════════
  
  /** Minimum length for risk IDs (SafeId constraint) */
  val MinIdLength = 3
  
  /** Maximum length for risk IDs (SafeId constraint) */
  val MaxIdLength = 30
  
  /** Maximum length for risk names (SafeName constraint) */
  val MaxNameLength = 50
}
