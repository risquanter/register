package app.state

/** The mode a form's shape inputs are entered in. The case name is the mode's
  * spelling everywhere it is serialised, so this enum declares no strings of
  * its own beyond the label a person reads. */
enum DistributionMode:
  case Metalog, Lognormal

object DistributionMode:
  /** The label a mode's option carries in a form. */
  def label(mode: DistributionMode): String = mode match
    case Metalog   => "Metalog (expert estimate)"
    case Lognormal => "Lognormal (BCG)"

  /** Every mode, as the options of a form's mode switch. */
  val options: List[(DistributionMode, String)] = values.toList.map(m => (m, label(m)))
