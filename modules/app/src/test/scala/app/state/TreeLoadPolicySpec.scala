package app.state

import zio.test.*

import com.risquanter.register.domain.data.iron.{TreeId, ScenarioName}

object TreeLoadPolicySpec extends ZIOSpecDefault:

  private val treeA = TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val treeB = TreeId.fromString("01HX9ABCDE0000000000000002").toOption.get
  private val branchX = ScenarioName.fromString("stress-2026").toOption.get
  private val branchY = ScenarioName.fromString("other-branch").toOption.get

  def spec = suite("TreeLoadPolicy.decide")(
    test("same tree, same branch (post-submit refresh) never needs confirmation, even if dirty") {
      assertTrue(
        TreeLoadPolicy.decide(Some(treeA), None, treeA, None, isDirty = true) == TreeLoadDecision.SameContext,
        TreeLoadPolicy.decide(Some(treeA), Some(branchX), treeA, Some(branchX), isDirty = true) == TreeLoadDecision.SameContext
      )
    },
    test("same tree, different branch, dirty builder needs confirmation") {
      // The case that could never be reached before branch-switch reloads existed.
      assertTrue(TreeLoadPolicy.decide(Some(treeA), None, treeA, Some(branchX), isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("same tree, different branch, clean builder reloads without asking") {
      assertTrue(TreeLoadPolicy.decide(Some(treeA), None, treeA, Some(branchX), isDirty = false) == TreeLoadDecision.ReloadClean)
    },
    test("different tree, same branch, dirty builder needs confirmation") {
      assertTrue(TreeLoadPolicy.decide(Some(treeA), Some(branchX), treeB, Some(branchX), isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("different tree, different branch, dirty builder needs confirmation") {
      assertTrue(TreeLoadPolicy.decide(Some(treeA), Some(branchX), treeB, Some(branchY), isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("no tree previously loaded, but a fresh draft in progress (isDirty), needs confirmation") {
      assertTrue(TreeLoadPolicy.decide(None, None, treeA, None, isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("no tree previously loaded, no draft in progress, reloads without asking") {
      assertTrue(TreeLoadPolicy.decide(None, None, treeA, None, isDirty = false) == TreeLoadDecision.ReloadClean)
    }
  )
