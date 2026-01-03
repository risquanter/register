package com.risquanter.register.domain

/**
 * Type aliases for simulation domain.
 * 
 * These provide semantic clarity and can be refined with Iron constraints in the future.
 */
package object data {
  /** Trial identifier (0-based index into simulation run) */
  type TrialId = Int
  
  /** Loss amount in base currency units (e.g., cents, pennies) */
  type Loss = Long
}
