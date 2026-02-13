package com.risquanter.register.configs

/** CORS configuration.
  *
  * Origins are normalised at construction time to handle both HOCON list
  * syntax and comma-separated env-var strings.  When `REGISTER_CORS_ORIGINS`
  * overrides the HOCON list via `$​{?REGISTER_CORS_ORIGINS}`, HOCON treats
  * the env-var value as a single scalar string (e.g. `"http://a,http://b"`),
  * producing a one-element list.  The constructor splits and trims each
  * entry so that `allowMatchingOrigins` receives individual origin strings.
  */
final case class CorsConfig private (allowedOrigins: List[String])

object CorsConfig:
  /** Smart constructor: splits comma-separated entries and trims whitespace. */
  def apply(rawOrigins: List[String]): CorsConfig =
    new CorsConfig(rawOrigins.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty))
