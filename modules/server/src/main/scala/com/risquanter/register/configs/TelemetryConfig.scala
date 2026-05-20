package com.risquanter.register.configs

import java.time.Duration

import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}

import com.risquanter.register.domain.data.iron.Url

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

  given DeriveConfig[Url.Url] = DeriveConfig(urlConfig)
  given DeriveConfig[TelemetryConfig] = DeriveConfig.derived
