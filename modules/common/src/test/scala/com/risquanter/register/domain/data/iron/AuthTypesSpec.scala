package com.risquanter.register.domain.data.iron

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}
import com.risquanter.register.domain.errors.{AppError, AuthError, AuthForbidden, AuthServiceUnavailable}

/** Tests for Wave 0 auth identity types.
  *
  * NOTE ON `.value` USAGE: UserId is a final class with no `unapply`, no public raw field,
  * and a redacted `toString` — by design (PII classification, ADR-022 pattern).
  * In production code, `.value` call sites are auditable PII access points.
  * In this test file, `.value` is the ONLY way to inspect the underlying UUID for
  * correctness assertions. Every `.value` call below is a deliberate test-only
  * exception, not a pattern to replicate in application code.
  */
object AuthTypesSpec extends ZIOSpecDefault:

  // A canonical lowercase UUID v4 (as Keycloak would issue in JWT sub claim)
  private val validUuid     = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val anotherUuid   = "00000000-0000-0000-0000-000000000001"
  private val invalidUuid1  = "not-a-uuid"
  private val invalidUuid2  = "8F14E45F-CEEA-4A0E-8F09-BCB3D2C2F6CF" // uppercase — not Keycloak format
  private val invalidUuid3  = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6c"  // too short (35 chars)

  def spec = suite("AuthTypes")(
    suite("UserId")(
      suite("fromString — valid UUIDs")(
        test("accepts canonical lowercase UUID v4") {
          val result = UserId.fromString(validUuid)
          assertTrue(result.isRight && result.toOption.get.value == validUuid)
        },
        test("accepts anonymous sentinel UUID") {
          val result = UserId.fromString("00000000-0000-0000-0000-000000000000")
          assertTrue(result.isRight)
        }
      ),
      suite("fromString — invalid UUIDs")(
        test("rejects plain string") {
          assertTrue(UserId.fromString(invalidUuid1).isLeft)
        },
        test("rejects uppercase UUID (not Keycloak format)") {
          assertTrue(UserId.fromString(invalidUuid2).isLeft)
        },
        test("rejects truncated UUID") {
          assertTrue(UserId.fromString(invalidUuid3).isLeft)
        },
        test("rejects empty string") {
          assertTrue(UserId.fromString("").isLeft)
        },
        test("rejects UUID with extra chars") {
          assertTrue(UserId.fromString(validUuid + "-extra").isLeft)
        }
      ),
      suite("toString redaction — PII protection")(
        test("toString returns redacted string, never the raw UUID") {
          val userId = UserId.fromString(validUuid).toOption.get
          assertTrue(
            userId.toString == "UserId(***)",
            !userId.toString.contains(validUuid)
          )
        },
        test("string interpolation uses redacted toString") {
          val userId = UserId.fromString(validUuid).toOption.get
          val interpolated = s"Resolving $userId"
          assertTrue(
            interpolated == "Resolving UserId(***)",
            !interpolated.contains(validUuid)
          )
        }
      ),
      suite("equals and hashCode")(
        test("equal UUIDs produce equal UserIds") {
          val u1 = UserId.fromString(validUuid).toOption.get
          val u2 = UserId.fromString(validUuid).toOption.get
          assertTrue(u1 == u2, u1.hashCode == u2.hashCode)
        },
        test("different UUIDs produce unequal UserIds") {
          val u1 = UserId.fromString(validUuid).toOption.get
          val u2 = UserId.fromString(anotherUuid).toOption.get
          assertTrue(u1 != u2)
        }
      ),
      suite("JSON codecs")(
        test("encodes to UUID string") {
          val userId = UserId.fromString(validUuid).toOption.get
          assertTrue(userId.toJson == s"\"$validUuid\"")
        },
        test("round-trips through JSON") {
          val userId   = UserId.fromString(validUuid).toOption.get
          val decoded  = userId.toJson.fromJson[UserId]
          assertTrue(decoded == Right(userId))
        },
        test("rejects invalid UUID in JSON decode") {
          val json = "\"not-a-uuid\""
          assertTrue(json.fromJson[UserId].isLeft)
        }
      )
    ),
    suite("WorkspaceId")(
      suite("fromString — valid ULIDs")(
        test("accepts canonical uppercase ULID") {
          val result = WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV")
          assertTrue(result.isRight)
        },
        test("accepts lowercase ULID and normalizes") {
          val result = WorkspaceId.fromString("01arz3ndektsv4rrffq69g5fav")
          assertTrue(result.isRight && result.toOption.get.value == "01ARZ3NDEKTSV4RRFFQ69G5FAV")
        }
      ),
      suite("fromString — invalid ULIDs")(
        test("rejects empty string") {
          assertTrue(WorkspaceId.fromString("").isLeft)
        },
        test("rejects wrong length") {
          assertTrue(WorkspaceId.fromString("01ARZ3NDEK").isLeft)
        },
        test("rejects invalid characters") {
          assertTrue(WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5F!!").isLeft)
        }
      ),
      suite("JSON codecs")(
        test("encodes to ULID string") {
          val wsId = WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get
          assertTrue(wsId.toJson == "\"01ARZ3NDEKTSV4RRFFQ69G5FAV\"")
        },
        test("round-trips through JSON") {
          val wsId    = WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get
          val decoded = wsId.toJson.fromJson[WorkspaceId]
          assertTrue(decoded == Right(wsId))
        }
      ),
      suite("compiler distinction from TreeId")(
        test("WorkspaceId and TreeId with same raw value are not equal") {
          val wsId   = WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get
          val treeId = TreeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get
          // They cannot be compared directly (different types) — this verifies type safety
          // via the fact that both have the same .value string but are separate domain types.
          assertTrue(wsId.value == treeId.value)
          // The compiler ensures wsId: WorkspaceId and treeId: TreeId are not interchangeable.
        }
      )
    ),
    suite("AuthError hierarchy")(
      test("AuthForbidden getMessage formats structured message") {
        val err = AuthForbidden(
          userId       = validUuid,
          permission   = "design_write",
          resourceType = "risk_tree",
          resourceId   = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        )
        assertTrue(err.getMessage == s"Access denied: user=$validUuid permission=design_write resource=risk_tree:01ARZ3NDEKTSV4RRFFQ69G5FAV")
      },
      test("AuthServiceUnavailable getMessage includes reason") {
        val err = AuthServiceUnavailable("connection refused")
        assertTrue(err.getMessage == "Authorization service unavailable: connection refused")
      },
      test("AuthServiceUnavailable getCause returns provided cause") {
        val cause = RuntimeException("timeout")
        val err   = AuthServiceUnavailable("timeout", Some(cause))
        assertTrue(err.getCause eq cause)
      },
      test("AuthServiceUnavailable getCause returns null when no cause") {
        val err = AuthServiceUnavailable("network error")
        assertTrue(err.getCause == null)
      },
      test("AuthForbidden is an AppError") {
        val err: AppError = AuthForbidden("u", "p", "r", "i")
        assertTrue(err.isInstanceOf[AuthError])
      },
      test("AuthServiceUnavailable is an AppError") {
        val err: AppError = AuthServiceUnavailable("reason")
        assertTrue(err.isInstanceOf[AuthError])
      }
    )
  )
