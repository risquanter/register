package com.risquanter.register.http.support

import com.risquanter.register.domain.data.iron.SafeUrl

object TestSafeUrls:
  val localhostOtlpEndpoint: SafeUrl = unsafe("http://localhost:4317")

  def unsafe(url: String): SafeUrl =
    SafeUrl.fromString(url).fold(
      errs => throw new IllegalArgumentException(errs.map(_.message).mkString("; ")),
      identity
    )