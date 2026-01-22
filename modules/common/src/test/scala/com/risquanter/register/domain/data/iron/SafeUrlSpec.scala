package com.risquanter.register.domain.data.iron

import zio.*
import zio.test.*
import zio.test.Assertion.*

object SafeUrlSpec extends ZIOSpecDefault:
  override def spec =
    suite("SafeUrlSpec")(
      test("accepts valid http/https service URLs") {
        val valid = List(
          "http://localhost:9080",
          "https://service",
          "http://service.svc.cluster.local:8080/api/v1",
          "http://10.0.0.1:9000/health",
          "https://example.com/path?query=1&x=y",
          "http://[::1]:8080/graphql",
          "https://sub.domain.example.org",
          "http://localhost",
          "https://example.com:443/",
          "http://service:3000",
          "http://service:3000/path/to/resource?x=1#frag"
        )
        val results = valid.map(SafeUrl.fromString(_))
        assertTrue(results.forall(_.isRight))
      },
      test("rejects unsupported or malformed URLs") {
        val invalid = List(
          "",
          "   ",
          "localhost:8080", // missing scheme
          "ftp://example.com", // unsupported scheme
          "ws://example.com", // unsupported scheme
          "http:/bad", // malformed
          "http://", // missing host
          "htp://example.com", // misspelled scheme
          "https:// example.com", // space after scheme
          "http://exa mple.com", // embedded space
          "https://[::1" // malformed ipv6
        )
        val results = invalid.map(SafeUrl.fromString(_))
        assertTrue(results.forall(_.isLeft))
      }
    )
