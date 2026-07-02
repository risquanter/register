package com.risquanter.register.auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}

object BootstrapProvisionerSpec extends ZIOSpecDefault:

  private val validUuid        = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val validUserId      = UserId.fromString(validUuid).toOption.get
  private val validWorkspaceId = WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get

  def spec = suite("BootstrapProvisioner")(
    suite("BootstrapProvisionerNoOp")(
      test("recordOwnership returns unit for any input") {
        for
          result <- BootstrapProvisionerNoOp.recordOwnership(validUserId, validWorkspaceId)
        yield assertTrue(result == ())
      },
      test("layer builds successfully and resolves BootstrapProvisioner") {
        for
          provisioner <- ZIO.service[BootstrapProvisioner]
          result      <- provisioner.recordOwnership(validUserId, validWorkspaceId)
        yield assertTrue(result == ())
      }.provide(BootstrapProvisionerNoOp.layer)
    ),
    suite("BootstrapProvisioner.noOp — companion inline val")(
      test("recordOwnership returns unit") {
        for
          result <- BootstrapProvisioner.noOp.recordOwnership(validUserId, validWorkspaceId)
        yield assertTrue(result == ())
      }
    )
  )
