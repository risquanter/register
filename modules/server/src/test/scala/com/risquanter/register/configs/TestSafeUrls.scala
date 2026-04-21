package com.risquanter.register.configs

import com.risquanter.register.domain.data.iron.SafeUrl

object TestSafeUrls:
  val localhostOtlpEndpoint: SafeUrl = unsafe("http://localhost:4317")
  val testOtlpEndpoint: SafeUrl = unsafe("http://test:4317")

  def unsafe(url: String): SafeUrl =
    SafeUrl.fromString(url).fold(
      errs => throw new IllegalArgumentException(errs.map(_.message).mkString("; ")),
      identity
    )