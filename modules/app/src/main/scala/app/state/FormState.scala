package app.state

import com.raquo.laminar.api.L.{*, given}

/**
 * FormState trait - reactive version with error display timing control.
 */
trait FormState:
  /** List of error signals for this form */
  def errorSignals: List[Signal[Option[String]]]
  
  /** Signal indicating whether any field has an error */
  lazy val hasErrors: Signal[Boolean] =
    Signal.combineSeq(errorSignals).map(_.exists(_.isDefined))
  
  /** Helper to parse a string as a Double */
  protected def parseDouble(s: String): Option[Double] =
    scala.util.Try(s.trim.toDouble).toOption
  
  /** Helper to parse a string as a Long */
  protected def parseLong(s: String): Option[Long] =
    scala.util.Try(s.trim.toLong).toOption
  
  /** Helper to parse a comma-separated string as a list of Doubles */
  protected def parseDoubleList(s: String): List[Double] =
    if s.isBlank then Nil
    else s.split(",").toList.flatMap(part => parseDouble(part.trim))

