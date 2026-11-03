package com.risquanter.register.common

/** The two bases a mitigation valuation can be read against.
  *
  * `symbol` is the name the query engine binds, so it is declared rather than
  * derived from the case name: renaming a case must not silently rename a
  * keyword of the query language. Reserved as a query symbol name, because a
  * constant of this sort used in any other slot binds as a type error.
  */
enum ValuationBasis(val symbol: String):
  case Inherent extends ValuationBasis("inherent")
  case Residual extends ValuationBasis("residual")
