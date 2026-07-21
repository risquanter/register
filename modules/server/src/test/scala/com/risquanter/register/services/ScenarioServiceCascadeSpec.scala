package com.risquanter.register.services

import zio.*
import zio.test.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, ScenarioName, WorkspaceId}
import com.risquanter.register.domain.errors.ScenarioHeadStale
import com.risquanter.register.testutil.TestHelpers.safeId

/** `cascadeDeleteScenarios` (the extension in `ScenarioService.scala`) used to
  * give up immediately on a `ScenarioHeadStale` failure — a scenario branch
  * updated concurrently with workspace teardown would be skipped and silently
  * orphaned in Irmin forever, the exact leak this cascade exists to close.
  * Fixed 2026-07-21: one retry against a freshly re-resolved head.
  */
object ScenarioServiceCascadeSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private val wsId: WorkspaceId = WorkspaceId(safeId("cascade-retry-ws"))
  private def name(s: String): ScenarioName.ScenarioName = ScenarioName.fromString(s).toOption.get
  private def hash(fill: Char): CommitHash = CommitHash.fromString(fill.toString * 40).toOption.get
  private def branchRef(n: ScenarioName.ScenarioName): BranchRef = BranchRef.scenario(wsId, n).toOption.get

  override def spec = suite("ScenarioService.cascadeDeleteScenarios")(

    test("retries once against a fresh head after a stale-head (concurrent mutation) failure, and succeeds") {
      val scenarioName = name("stress-2026")
      val staleHead    = hash('a')
      val freshHead    = hash('b')
      for
        listCalls <- Ref.make(0)
        deleted   <- Ref.make(List.empty[CommitHash])
        svc        = CascadeTestStubs.scenarioService(
                       onList = _ => listCalls.updateAndGet(_ + 1).map { n =>
                         // First `list` (cascadeDeleteScenarios' own) observes the stale
                         // head — a concurrent write already moved the branch by the time
                         // this runs. The retry's re-list observes the real, current head.
                         val head = if n == 1 then staleHead else freshHead
                         List(ScenarioSummary(scenarioName, head))
                       },
                       onDelete = (_, _, head) =>
                         if head == staleHead then
                           ZIO.fail(ScenarioHeadStale(branchRef(scenarioName), staleHead, Some(freshHead)))
                         else
                           deleted.update(_ :+ head).unit
                     )
        _         <- svc.cascadeDeleteScenarios(wsId)
        result    <- deleted.get
        calls     <- listCalls.get
      yield assertTrue(
        result == List(freshHead), // the retry's delete succeeded with the fresh head
        calls == 2                 // one list from cascadeDeleteScenarios itself, one from the retry
      )
    },

    test("gives up quietly (no crash) if the scenario is already gone by the retry") {
      val scenarioName = name("stress-2026")
      val staleHead    = hash('a')
      for
        listCalls <- Ref.make(0)
        svc        = CascadeTestStubs.scenarioService(
                       onList = _ => listCalls.updateAndGet(_ + 1).map { n =>
                         if n == 1 then List(ScenarioSummary(scenarioName, staleHead)) else Nil
                       },
                       onDelete = (_, _, _) =>
                         ZIO.fail(ScenarioHeadStale(branchRef(scenarioName), staleHead, None))
                     )
        exit      <- svc.cascadeDeleteScenarios(wsId).exit
      yield assertTrue(exit.isSuccess) // best-effort: never fails, even when the retry finds nothing left
    }
  )
