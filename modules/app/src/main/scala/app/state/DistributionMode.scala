package app.state

/** Distribution mode enum for the form */
enum DistributionMode:
  case Expert, Lognormal
  
  def toApiString: String = this match
    case Expert => "expert"
    case Lognormal => "lognormal"

object DistributionMode:
  def fromString(s: String): Option[DistributionMode] = s.toLowerCase match
    case "expert" => Some(Expert)
    case "lognormal" => Some(Lognormal)
    case _ => None
