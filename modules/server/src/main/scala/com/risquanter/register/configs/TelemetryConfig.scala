package com.risquanter.register.configs

import java.time.Duration

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
  otlpEndpoint: String,
  devExportIntervalSeconds: Int,
  prodExportIntervalSeconds: Int
) {
  
  /** Development metric export interval as Duration */
  def devExportInterval: Duration = Duration.ofSeconds(devExportIntervalSeconds.toLong)
  
  /** Production metric export interval as Duration */
  def prodExportInterval: Duration = Duration.ofSeconds(prodExportIntervalSeconds.toLong)
}
