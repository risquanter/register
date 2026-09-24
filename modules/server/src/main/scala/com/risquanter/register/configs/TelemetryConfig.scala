package com.risquanter.register.configs

import java.time.Duration

import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}

import com.risquanter.register.domain.data.iron.Url

/** Where recorded metrics and traces are sent. This is a separate pipeline from
  * application logging, which always goes to standard output through Logback.
  *
  * `Console` prints each span and each metric as text on the running process's
  * own output, for development with no collector running. `Otlp` sends them to
  * the collector at `otlpEndpoint`, which decides where they go from there.
  */
enum TelemetryExporter:
  case Console, Otlp

object TelemetryExporter:

  /** Parses the configured name, ignoring case and surrounding whitespace. An
    * unrecognised name is an error rather than a silent default, so a mistyped
    * exporter fails at startup instead of quietly selecting the wrong one. */
  def fromString(s: String): Either[String, TelemetryExporter] =
    s.trim.toLowerCase match
      case "console" => Right(TelemetryExporter.Console)
      case "otlp"    => Right(TelemetryExporter.Otlp)
      case other     =>
        Left(s"unknown telemetry exporter '$other': expected 'console' or 'otlp'")

/** OpenTelemetry configuration
  *
  * Centralizes all telemetry settings:
  * - Service identification (name, scope)
  * - Which exporter the SDK is built with, and the OTLP endpoint it uses
  * - Metric export intervals for dev/prod
  *
  * Follows the same pattern as SimulationConfig and ServerConfig.
  */
final case class TelemetryConfig(
  serviceName: String,
  instrumentationScope: String,
  exporter: TelemetryExporter,
  otlpEndpoint: Url.Url,
  devExportIntervalSeconds: Int,
  prodExportIntervalSeconds: Int
) {
  
  /** Development metric export interval as Duration */
  def devExportInterval: Duration = Duration.ofSeconds(devExportIntervalSeconds.toLong)
  
  /** Production metric export interval as Duration */
  def prodExportInterval: Duration = Duration.ofSeconds(prodExportIntervalSeconds.toLong)
}

object TelemetryConfig:
  private val urlConfig: Config[Url.Url] =
    Config.string.mapOrFail { s =>
      Url
        .fromString(s, "otlpEndpoint")
        .left
        .map(errs => Config.Error.InvalidData(message = errs.map(_.message).mkString("; ")))
    }

  private val exporterConfig: Config[TelemetryExporter] =
    Config.string.mapOrFail { s =>
      TelemetryExporter
        .fromString(s)
        .left
        .map(message => Config.Error.InvalidData(message = message))
    }

  given DeriveConfig[Url.Url] = DeriveConfig(urlConfig)
  given DeriveConfig[TelemetryExporter] = DeriveConfig(exporterConfig)
  given DeriveConfig[TelemetryConfig] = DeriveConfig.derived
