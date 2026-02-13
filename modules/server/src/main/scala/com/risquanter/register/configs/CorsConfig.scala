package com.risquanter.register.configs

/** CORS configuration.
  *
  * Origins are normalised at construction time to handle both HOCON list
  * syntax and comma-separated env-var strings.  When `REGISTER_CORS_ORIGINS`
  * overrides the HOCON list via `$​{?REGISTER_CORS_ORIGINS}`, HOCON treats
  * the env-var value as a single scalar string (e.g. `"http://a,http://b"`),
  * producing a one-element list.
  *
  * `DeriveConfig` (magnolia) calls the primary constructor directly,
  * bypassing any companion `apply`.  Therefore normalisation happens
  * eagerly inside the class body so it works regardless of how
  * the instance is created.
  */
final case class CorsConfig(allowedOrigins: List[String]):
  /** Normalised origins — comma-separated entries split and trimmed. */
  val normalised: List[String] =
    allowedOrigins.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty)
