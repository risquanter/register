package com.risquanter.register.configs

import java.time.Duration

import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}

import com.risquanter.register.domain.data.iron.SafeUrl

/** OpenTelemetry configuration
  * 
  * Centralizes all telemetry settings:
  * - Service identification (name, scope)
  * - OTLP exporter endpoint
  * - Metric export intervals for dev/prod
  * 
  * Follows the same pattern as SimulationConfig and ServerConfig.
  */
final case class TelemetryConfig(
  serviceName: String,
  instrumentationScope: String,
  otlpEndpoint: SafeUrl,
  devExportIntervalSeconds: Int,
  prodExportIntervalSeconds: Int
) {
  
  /** Development metric export interval as Duration */
  def devExportInterval: Duration = Duration.ofSeconds(devExportIntervalSeconds.toLong)
  
  /** Production metric export interval as Duration */
  def prodExportInterval: Duration = Duration.ofSeconds(prodExportIntervalSeconds.toLong)
}

object TelemetryConfig:
  private val safeUrlConfig: Config[SafeUrl] =
    Config.string.mapOrFail { s =>
      SafeUrl
        .fromString(s, "otlpEndpoint")
        .left
        .map(errs => Config.Error.InvalidData(message = errs.map(_.message).mkString("; ")))
    }

  given DeriveConfig[SafeUrl] = DeriveConfig(safeUrlConfig)
  given DeriveConfig[TelemetryConfig] = DeriveConfig.derived
