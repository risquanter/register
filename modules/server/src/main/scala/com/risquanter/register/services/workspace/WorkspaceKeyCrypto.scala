package com.risquanter.register.services.workspace

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match
import _root_.zio.{UIO, ZIO}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, WorkspaceKeyHash}

/**
  * Server-side cryptography for workspace capability keys.
  *
  * The key types (`WorkspaceKeySecret`, `WorkspaceKeyHash`) live in the
  * cross-compiled `common` module because the domain model and DTOs reference
  * them. Their generation and hashing are JVM-only (`SecureRandom`, SHA-256),
  * so they live here — off the cross-compiled types — keeping `common` free of
  * any `java.security` reference that would otherwise be a Scala.js link hazard.
  *
  * Plain object, called directly at its use sites — same shape as
  * `ContentHashIndex` (SHA-256 over domain content, returning a refined type).
  */
object WorkspaceKeyCrypto {

  // Thread-safe: SecureRandom is documented as thread-safe in the JDK.
  // Shared instance avoids repeated seeding overhead from /dev/urandom on each call.
  private val rng: SecureRandom = new SecureRandom()

  /** Generate a cryptographically random workspace key (128-bit entropy).
    * refineUnsafe is safe here: SecureRandom(16 bytes) → base64url encoding
    * always produces exactly 22 chars from [A-Za-z0-9_-].
    */
  def generate: UIO[WorkspaceKeySecret] =
    ZIO.succeed {
      val bytes = new Array[Byte](16) // 128 bits
      rng.nextBytes(bytes)
      val encoded = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
      WorkspaceKeySecret(encoded.refineUnsafe[Match["^[A-Za-z0-9_-]{22}$"]])
    }

  /** SHA-256 lookup digest of a workspace capability key.
    * refineUnsafe is safe here: a SHA-256 hex rendering always satisfies
    * ^[0-9a-f]{64}$.
    */
  def hash(secret: WorkspaceKeySecret): WorkspaceKeyHash =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(secret.reveal.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
    WorkspaceKeyHash(digest.refineUnsafe[Match["^[0-9a-f]{64}$"]])
}
