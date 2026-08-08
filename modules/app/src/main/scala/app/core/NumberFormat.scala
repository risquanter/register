package app.core

/** Numeric display formatting shared across form fields and chart labels. */
object NumberFormat:

  /** A 0–1 domain value as its 0–100 percent string (no "%" suffix), rounded
    * half-up to `decimals` places via BigDecimal — eliminating floating-point
    * noise (e.g. `0.1 * 100 = 10.000000000000001`) — with trailing zeros
    * stripped.
    *
    * - `decimals = 0` → percentiles (integers: "10", "50", "90")
    * - `decimals = 2` → probability ("20.5" — trailing zeros stripped)
    */
  def percentValue(p: Double, decimals: Int): String =
    BigDecimal(p * 100.0)
      .setScale(decimals, scala.math.BigDecimal.RoundingMode.HALF_UP)
      .underlying.stripTrailingZeros.toPlainString
