package com.risquanter.register.auth

import zio.*
import zio.test.*

import com.risquanter.register.configs.{SpiceDbConfig, SpiceDbConsistency, SpiceDbToken, TestConfigs}
import com.risquanter.register.domain.data.iron.{IronConstants, MeshServiceUrl, TreeId, UserId, WorkspaceId}
import com.risquanter.register.domain.errors.{AuthForbidden, AuthServiceUnavailable}
import com.risquanter.register.telemetry.{MetricsLive, TracingLive}
import com.risquanter.register.testcontainers.SpiceDbCompose
import com.risquanter.register.auth.ResourceRef.asResource

/** Integration tests for the SpiceDB HTTP adapter (T-S1 through T-S10).
  *
  * Requires Docker + docker compose CLI. SpiceDB is started automatically via
  * [[SpiceDbCompose]] and torn down after the suite.
  *
  * Pre-seeded state (by [[SpiceDbCompose.layer]]):
  *   - `workspace:ws1#owner_user@user:alice`
  *   - `workspace:ws1#viewer@user:carol`
  *   - `workspace:ws2#viewer@user:alice`
  *   - `risk_tree:tree1#workspace@workspace:ws1`
  *
  * Run: sbt "serverIt/testOnly *AuthorizationServiceSpiceDBItSpec"
  *
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §SpiceDB Adapter Integration Tests
  * @see AUTH-PHASES.md Phase 3
  */
object AuthorizationServiceSpiceDBItSpec extends ZIOSpecDefault:

  // ─── Fixtures ───────────────────────────────────────────────────────────────

  private val alice:    UserId.Authenticated = UserId.fromString(SpiceDbCompose.aliceUuid).toOption.get
  private val bob:      UserId.Authenticated = UserId.fromString(SpiceDbCompose.bobUuid).toOption.get
  private val carol:    UserId.Authenticated = UserId.fromString(SpiceDbCompose.carolUuid).toOption.get
  private val sentinel: UserId.Authenticated = UserId.fromString(SpiceDbCompose.sentinelUuid).toOption.get

  private val ws1:   WorkspaceId = WorkspaceId.fromString(SpiceDbCompose.ws1Id).toOption.get
  private val ws3:   WorkspaceId = WorkspaceId.fromString(SpiceDbCompose.ws3Id).toOption.get
  private val tree1: TreeId      = TreeId.fromString(SpiceDbCompose.tree1Id).toOption.get

  // ─── Shared layer ───────────────────────────────────────────────────────────

  // Started once per suite run; schema + seed data applied during acquisition.
  private val sharedLayer: ZLayer[Any, Throwable, AuthorizationService & BootstrapProvisioner & SpiceDbCompose.Resource] =
    ZLayer.make[AuthorizationService & BootstrapProvisioner & SpiceDbCompose.Resource](
      SpiceDbCompose.layer,
      SpiceDbCompose.configLayer,
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      AuthorizationServiceSpiceDB.liveLayer,
      BootstrapProvisionerSpiceDB.liveLayer
    )

  // ─── Isolated-config helper ─────────────────────────────────────────────────

  // Builds a temporary AuthorizationService with a custom config in a ZIO.scoped block.
  // Used by T-S5 (unreachable URL) and T-S6 (wrong token).
  private def scopedAuthzWith(config: SpiceDbConfig): ZIO[Scope, Throwable, AuthorizationService] =
    ZLayer.make[AuthorizationService](
      ZLayer.succeed(config),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      AuthorizationServiceSpiceDB.liveLayer
    ).build.map(_.get[AuthorizationService])

  // ─── Test suite ─────────────────────────────────────────────────────────────

  def spec = suite("AuthorizationServiceSpiceDB — integration (T-S1 through T-S10)")(

    // ── T-S1: Allowed check returns Checked[P] ──────────────────────────────
    test("T-S1 — allowed check succeeds (owner_user → view_workspace)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1.asResource)
      ).map(_ => assertCompletes)
    },

    // ── T-S2: Denied check returns AuthForbidden ────────────────────────────
    test("T-S2 — denied check returns AuthForbidden with populated fields") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(bob, Permission.ViewWorkspace, ws1.asResource)
      ).either.map {
        case Left(err: AuthForbidden) =>
          assertTrue(
            err.userId       == SpiceDbCompose.bobUuid,
            err.permission   == "view_workspace",
            err.resourceType == "workspace",
            err.resourceId   == SpiceDbCompose.ws1Id
          )
        case other =>
          assertTrue(false)
      }
    },

    // ── T-S3: Insufficient permission returns AuthForbidden ─────────────────
    test("T-S3 — viewer cannot design_write (insufficient permission)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(carol, Permission.DesignWrite, ws1.asResource)
      ).either.map {
        case Left(_: AuthForbidden) => assertCompletes
        case other                  => assertTrue(false)
      }
    },

    // ── T-S4: Schema inheritance (owner_user → view_tree via workspace) ─────
    test("T-S4 — workspace owner_user grants view_tree via schema inheritance") {
      // alice has no direct tree relationship; access flows via:
      //   workspace:ws1#owner_user → view_workspace → risk_tree:tree1#workspace → view_tree
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewTree, tree1.asResource)
      ).map(_ => assertCompletes)
    },

    // ── T-S5: SpiceDB unreachable returns AuthServiceUnavailable ────────────
    test("T-S5 — unreachable SpiceDB returns AuthServiceUnavailable (fail-closed)") {
      val deadConfig = SpiceDbConfig(
        url            = MeshServiceUrl.fromString("http://localhost:1").toOption.get,
        token          = SpiceDbToken.fromString("any-token").toOption.get,
        consistency    = SpiceDbConsistency.MinimizeLatency,
        timeoutSeconds = IronConstants.One   // 1 s — fail fast
      )
      ZIO.scoped {
        scopedAuthzWith(deadConfig).flatMap(
          _.check(alice, Permission.ViewWorkspace, ws1.asResource).either
        )
      }.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    },

    // ── T-S6: Invalid token returns AuthServiceUnavailable (fail-closed) ────
    test("T-S6 — wrong bearer token returns AuthServiceUnavailable (4xx maps to unavailable)") {
      ZIO.service[SpiceDbCompose.Resource].flatMap { res =>
        val wrongTokenConfig = SpiceDbConfig(
          url            = MeshServiceUrl.fromString(res.baseUrl).toOption.get,
          token          = SpiceDbToken.fromString("wrong-token-rejected").toOption.get,
          consistency    = SpiceDbConsistency.MinimizeLatency,
          timeoutSeconds = IronConstants.Ten
        )
        ZIO.scoped {
          scopedAuthzWith(wrongTokenConfig).flatMap(
            _.check(alice, Permission.ViewWorkspace, ws1.asResource).either
          )
        }
      }.map {
        // SpiceDB returns 401 for the wrong preshared key.
        // The adapter maps any non-2xx to AuthServiceUnavailable (fail-closed §L2.2).
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    },

    // ── T-S7: listAccessible returns both workspace IDs for alice ────────────
    test("T-S7 — listAccessible returns all workspaces where alice has view_workspace") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(alice, ResourceType.Workspace, Permission.ViewWorkspace)
      ).map { ids =>
        val idStrings = ids.map(_.value.toString).toSet
        assertTrue(
          idStrings.contains(SpiceDbCompose.ws1Id),
          idStrings.contains(SpiceDbCompose.ws2Id)
        )
      }
    },

    // ── T-S8: listAccessible returns Nil for user with no relationships ───────
    test("T-S8 — listAccessible returns Nil for bob (no relationships)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(bob, ResourceType.Workspace, Permission.ViewWorkspace)
      ).map { ids =>
        assertTrue(ids.isEmpty)
      }
    },

    // ── T-S9: Anonymous sentinel UUID has no permissions ─────────────────────
    test("T-S9 — anonymous sentinel UUID (00000000-...) has no permissions") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(sentinel, Permission.ViewWorkspace, ws1.asResource)
      ).either.map {
        case Left(_: AuthForbidden) => assertCompletes
        case other                  => assertTrue(false)
      }
    },

    // ── T-S10: BootstrapProvisionerSpiceDB.recordOwnership writes a checkable tuple
    test("T-S10 — recordOwnership writes an immediately-readable tuple (FullyConsistent)") {
      // Precondition: alice has no relationship to ws3 (not in seed data).
      // Step 1: write ownership. Step 2: check permission — must succeed.
      for
        _ <- ZIO.serviceWithZIO[BootstrapProvisioner](
               _.recordOwnership(alice, ws3)
             )
        _ <- ZIO.serviceWithZIO[AuthorizationService](
               _.check(alice, Permission.ViewWorkspace, ws3.asResource)
             )
      yield assertCompletes
    }

  ).provideLayerShared(sharedLayer)

end AuthorizationServiceSpiceDBItSpec
