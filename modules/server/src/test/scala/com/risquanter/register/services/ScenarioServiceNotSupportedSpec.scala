package com.risquanter.register.services

import zio.*
import zio.prelude.NonEmptyList
import zio.test.*

import com.risquanter.register.auth.{Authority, Checked, TestChecked}
import com.risquanter.register.domain.data.iron.{CommitHash, ScenarioName, WorkspaceId}
import com.risquanter.register.domain.errors.{Core, Diagnostic, ErrorLocation, Failure, TrustZone}
import com.risquanter.register.http.errors.Capability
import com.risquanter.register.testutil.TestHelpers.safeId

/** The in-memory deployment's scenario and merge services: every method fails
  * as `NotSupported` for scenarios at the deployment's zone, naming the setting
  * that enables them in the log.
  */
object ScenarioServiceNotSupportedSpec extends ZIOSpecDefault:

  private given Checked[Authority] = TestChecked.value

  private val wsId: WorkspaceId                   = WorkspaceId(safeId("not-supported-ws"))
  private val scenario: ScenarioName.ScenarioName = ScenarioName.fromString("draft").toOption.get
  private val head: CommitHash                    = CommitHash.fromString("a" * 40).toOption.get

  private val scenariosUnavailable: Failure =
    Failure.NotSupported(Core(TrustZone.Environment, NonEmptyList(ErrorLocation.Whole -> Capability.Scenarios),
      List(Diagnostic.PeerMessage("Scenarios require the Irmin backend (repository.type=irmin)"))))

  def spec = suite("ScenarioServiceNotSupported")(
    test("every scenario and merge method fails as NotSupported for scenarios") {
      for
        create  <- ScenarioServiceNotSupported.create(wsId, scenario, ScenarioSource.Main).either
        list    <- ScenarioServiceNotSupported.list(wsId).either
        delete  <- ScenarioServiceNotSupported.delete(wsId, scenario, head).either
        preview <- ScenarioMergeServiceNotSupported.preview(wsId, scenario).either
        merge   <- ScenarioMergeServiceNotSupported.merge(wsId, scenario).either
      yield assertTrue(
        create == Left(scenariosUnavailable),
        list == Left(scenariosUnavailable),
        delete == Left(scenariosUnavailable),
        preview == Left(scenariosUnavailable),
        merge == Left(scenariosUnavailable)
      )
    }
  )
