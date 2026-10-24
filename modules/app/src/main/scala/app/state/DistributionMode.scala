package app.state

/** The mode a leaf form is editing. The case name is the mode's spelling
  * everywhere it is serialised, so this enum declares no strings of its own. */
enum DistributionMode:
  case Metalog, Lognormal
