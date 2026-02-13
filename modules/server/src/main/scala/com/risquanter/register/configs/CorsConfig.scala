package com.risquanter.register.configs

/** CORS configuration.
  *
  * `allowedOrigins` is normalised to handle both HOCON list syntax and
  * comma-separated env-var strings.  When `REGISTER_CORS_ORIGINS` overrides
  * the HOCON list via `$​{?REGISTER_CORS_ORIGINS}`, HOCON treats the env-var
  * value as a single scalar string (e.g. `"http://a,http://b"`), producing
  * a one-element list.  The smart constructor splits and trims each entry
  * so that `allowMatchingOrigins` receives individual origin strings.
  */
final case class CorsConfig(
  allowedOrigins: List[String]
)

object CorsConfig:
  /** Normalise raw values: split any comma-separated entries and trim whitespace. */
  def normalise(raw: List[String]): CorsConfig =
    CorsConfig(raw.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty))
