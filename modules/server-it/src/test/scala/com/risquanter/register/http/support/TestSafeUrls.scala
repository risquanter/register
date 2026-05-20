package com.risquanter.register.http.support

import com.risquanter.register.domain.data.iron.Url

object TestSafeUrls:
  val localhostOtlpEndpoint: Url.Url = unsafe("http://localhost:4317")

  def unsafe(url: String): Url.Url =
    Url.fromString(url).fold(
      errs => throw new IllegalArgumentException(errs.map(_.message).mkString("; ")),
      identity
    )