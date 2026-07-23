package app.state

import zio.test.*

import com.risquanter.register.domain.data.iron.{BranchChoice, TreeId, ScenarioName}

object TreeLoadPolicySpec extends ZIOSpecDefault:

  private val treeA = TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val treeB = TreeId.fromString("01HX9ABCDE0000000000000002").toOption.get
  private val main    = BranchChoice.Main
  private val branchX = BranchChoice.Scenario(ScenarioName.fromString("stress-2026").toOption.get)
  private val branchY = BranchChoice.Scenario(ScenarioName.fromString("other-branch").toOption.get)

  def spec = suite("TreeLoadPolicy.decide")(
    test("same tree, same branch (post-submit refresh) never needs confirmation, even if dirty") {
      assertTrue(
        TreeLoadPolicy.decide(Some(treeA), main, treeA, main, isDirty = true) == TreeLoadDecision.SameContext,
        TreeLoadPolicy.decide(Some(treeA), branchX, treeA, branchX, isDirty = true) == TreeLoadDecision.SameContext
      )
    },
    test("same tree, different branch, dirty builder needs confirmation") {
      // The case that could never be reached before branch-switch reloads existed.
      assertTrue(TreeLoadPolicy.decide(Some(treeA), main, treeA, branchX, isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("same tree, different branch, clean builder reloads without asking") {
      assertTrue(TreeLoadPolicy.decide(Some(treeA), main, treeA, branchX, isDirty = false) == TreeLoadDecision.ReloadClean)
    },
    test("different tree, same branch, dirty builder needs confirmation") {
      assertTrue(TreeLoadPolicy.decide(Some(treeA), branchX, treeB, branchX, isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("different tree, different branch, dirty builder needs confirmation") {
      assertTrue(TreeLoadPolicy.decide(Some(treeA), branchX, treeB, branchY, isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("no tree previously loaded, but a fresh draft in progress (isDirty), needs confirmation") {
      assertTrue(TreeLoadPolicy.decide(None, main, treeA, main, isDirty = true) == TreeLoadDecision.NeedsConfirm)
    },
    test("no tree previously loaded, no draft in progress, reloads without asking") {
      assertTrue(TreeLoadPolicy.decide(None, main, treeA, main, isDirty = false) == TreeLoadDecision.ReloadClean)
    }
  )
