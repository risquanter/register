package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*

/** 
 * Configuration layer factory utilities following BCG pattern.
 * 
 * Provides generic helper to create ZIO layers from HOCON config paths.
 */
object Configs {
  /** 
   * Generic layer factory for configuration case classes.
   * 
   * @param path Dot-separated config path (e.g., "register.server")
   * @tparam C Configuration case class type with DeriveConfig instance
   * @return ZLayer that provides the configuration
   */
  def makeLayer[C: DeriveConfig: Tag](path: String): ZLayer[Any, Throwable, C] = {
    val pathArr = path.split("\\.")
    ZLayer.fromZIO(
      ZIO.config(deriveConfig[C].nested(pathArr.head, pathArr.tail*))
    )
  }
}
