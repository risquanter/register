package com.risquanter.register.http.support

import zio.*
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend

import com.risquanter.register.http.HttpTestHarness.RunningServer

/** STTP backend + base URL wired to a running HTTP harness server. */
object SttpClientFixture:
  final case class Client(backend: SttpBackend[Task, Any], baseUrl: String)

  val layer: ZLayer[Scope & RunningServer, Throwable, Client] =
    ZLayer.scoped {
      for
        server  <- ZIO.service[RunningServer]
        backend <- HttpClientZioBackend.scoped()
      yield Client(backend, server.baseUrl)
    }
