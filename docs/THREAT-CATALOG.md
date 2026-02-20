# Threat Catalog — Auth Mesh Trust Invariants

> Machine-readable YAML per invariant. Each section's heading is the stable anchor target.
> Related ADR: [ADR-012 §7](./ADR-012.md#7-mesh-trust-assumptions-security-invariants)
> Extended T4 analysis: [Appendix below](#appendix-t4-sentinel-threat-model--extended-analysis)

---

## Schema

```yaml
# Catalog-level metadata shared across all entries.
catalog:
  schema_version: "1.0"
  domain: auth-mesh-trust
  related_adr: ADR-012
  last_updated: "2026-02-20"

# Fields per threat entry:
#   id              - stable identifier, never renamed
#   label           - human-readable name (same as section heading below)
#   invariant       - the condition that must hold; stated positively
#   failure_mode    - what happens if the invariant is violated
#   likelihood      - one of: infrastructure-incident | misconfiguration | transition-period-only
#   wave_blocker    - Wave number this must be resolved before, or null
#   status          - one of: open | verified-in-staging | addressed
#   ci_gate         - CI job that enforces the invariant
#   mitigations     - ordered list of controls
#   todo            - outstanding work item, null if none
#   test            - description + shell command to verify the invariant
#   related_code    - codebase locations directly involved
```

---

## T1: Mesh Bypass Prevention

```yaml
id: T1
label: Mesh Bypass Prevention
invariant: >
  All external traffic enters exclusively via the Istio waypoint proxy.
  No direct pod-to-pod or external-to-pod path exists that bypasses L7 policy.
failure_mode: >
  Attacker reaches pod directly via cluster-internal network (compromised neighbour pod,
  CI runner, debug container). In Wave 1 (noOp), workspace key becomes the sole access
  control. In Wave 2+ (requirePresent), forged x-user-id is accepted as infrastructure-
  asserted identity, bypassing all JWT validation.
likelihood: infrastructure-incident
wave_blocker: 2
status: open
ci_gate:
  type: k8s-job
  job_name: mesh-bypass-probe
  pipeline: staging-ci
  blocking: true
  must_pass_before: requirePresent-activation
mitigations:
  - "NetworkPolicy: deny all ingress to register-server pod except from Istio waypoint service account"
  - "No NodePort or HostPort exposure on app services"
  - "mesh-bypass-probe CI job: kubectl run against pod IP, expect connection refused"
todo: >
  [Wave 2 blocker / Phase K.1]
  Author NetworkPolicy manifest (infra/k8s/network-policy.yaml).
  Policy must deny direct pod ingress and all NodePort/HostPort.
  mesh-bypass-probe Job must be a BLOCKING step in staging CI pipeline.
  Must be applied before requirePresent activates in any namespace.
test:
  description: >
    Attempt direct pod IP access from outside the waypoint path.
    Expect TCP RST / connection refused — never an HTTP response.
  command: |
    kubectl run mesh-bypass-probe --rm -it --restart=Never \
      --image=curlimages/curl -- \
      curl -sf --max-time 3 http://<POD_IP>:8080/health
    # Expected: non-zero exit (refused/timeout). Exit 0 = FAIL.
related_code: []
```

---

## T2: JWT Validation Active

```yaml
id: T2
label: JWT Validation Active
invariant: >
  The Istio waypoint validates JWT signature and expiry on every inbound request
  before injecting x-user-id. Invalid or expired tokens are rejected at the mesh
  layer before the request reaches the application.
failure_mode: >
  Expired or forged tokens accepted. Arbitrary x-user-id injection becomes possible
  for any client that can construct a plausible (but invalid) JWT.
likelihood: misconfiguration
wave_blocker: null
status: verified-in-staging
ci_gate:
  type: curl-integration-test
  pipeline: staging-ci
  blocking: true
  must_pass_before: production-promotion
mitigations:
  - "RequestAuthentication resource with correct JWKS URI and issuer claim"
  - "Istio rejects invalid/expired JWTs with 401 before app is reached"
todo: null
test:
  description: >
    Send a request with an invalid/expired JWT to the waypoint ingress path.
    Expect 401 from Istio (no JSON body) — not 403 from app, not 200.
    Distinguish: Istio 401 has no JSON body; app 403 has ErrorResponse JSON body.
  command: |
    curl -sf \
      -H "Authorization: Bearer INVALID_TOKEN" \
      https://<INGRESS>/w/<key>/risk-trees
    # Expected: 401, no JSON body (Istio rejection).
    # App 403 has ErrorResponse JSON. 200 = JWT validation not active.
related_code: []
```

---

## T3: Header Stripping Active

```yaml
id: T3
label: Header Stripping Active
invariant: >
  The waypoint/ingress strips all client-supplied x-user-* headers (x-user-id,
  x-user-email, x-user-roles) from external requests BEFORE JWT validation and
  claim injection run. No external client can inject a pre-formed identity header.
failure_mode: >
  Client sends x-user-id: <victim-uuid> with no JWT. App receives it as if it were
  mesh-injected (infrastructure-asserted identity). Complete JWT bypass on all routes.
likelihood: misconfiguration
wave_blocker: 2
status: open
ci_gate:
  type: curl-integration-test
  pipeline: staging-ci
  blocking: true
  must_pass_before: requirePresent-activation
mitigations:
  - "EnvoyFilter or waypoint header policy stripping x-user-id, x-user-email, x-user-roles"
  - "Must be configured at ingress, not ztunnel (ztunnel is L4 only)"
  - "Staging criterion: forged header with no JWT → 401/403, no x-user-id in app structured log"
todo: >
  [Must be CI-gated before Wave 2]
  Verify stripping is active in staging before requirePresent wiring.
  Test MUST run against a requirePresent deployment — noOp ignores the header,
  making the test meaningless in noOp mode.
test:
  description: >
    Send a forged x-user-id with no JWT. Verify the app's structured log does NOT
    contain the forged userId. Must run against a requirePresent deployment.
  command: |
    curl -sf \
      -H "x-user-id: 00000000-0000-0000-0000-000000000001" \
      https://<INGRESS>/w/<key>/risk-trees
    # Expected: 401/403. App structured log must NOT contain userId=00000000-...-0001.
    # If it does: header stripping is broken.
related_code: []
```

---

## T4: Sentinel UUID Exclusion from SpiceDB

```yaml
id: T4
label: Sentinel UUID Exclusion from SpiceDB
invariant: >
  The anonymous sentinel UUID (00000000-0000-0000-0000-000000000000) is never granted
  permissions in SpiceDB. If it were, and if T1+T3 also fail simultaneously, an
  unauthenticated request could carry sentinel-level permissions.
failure_mode: >
  Relevant only during mode transition testing. In steady-state Layer 0 (noOp),
  SpiceDB is never called — pollution cannot propagate. In steady-state Layer 1+2,
  Keycloak UUID v4 generation makes the sentinel mathematically impossible as a real
  sub claim (probability 2^-122). The risk window is: sentinel test tuples left in
  SpiceDB after provisioning tests, combined with simultaneous T1+T3 failure.
likelihood: transition-period-only
wave_blocker: 3
status: open
ci_gate:
  type: zed-permission-check
  job_name: authz-provisioning-sentinel-assertion
  pipeline: authz-migration-ci
  blocking: true
  must_pass_before: fine-grained-mode-activation
mitigations:
  - point_in_time: >
      CI check: zed permission check with sentinel subject after every SpiceDB migration.
      Expected: PERMISSIONSHIP_NO_PERMISSION. Any other result = migration cleanup required.
  - continuous: >
      Wave 3 CEL caveat (non_sentinel_user) attached to all relationship write paths.
      SpiceDB engine evaluates caveat on every check call — sentinel tuples are structurally
      ignored regardless of what is stored. Converts a point-in-time check into a
      continuous enforcement invariant.
  - startup_observable: >
      logStartupMode() emits auth.sentinel.active=true when noOp is wired.
      Production namespace alerting on this field detects mode mismatch.
todo: >
  [Wave 3 blocker]
  Author non_sentinel_user CEL caveat in infra/spicedb/schema.zed.
  Apply to all relationship write paths in AuthzProvisioning.
  Verify: zed permission check with sentinel after manually writing a sentinel tuple
  must still return PERMISSIONSHIP_NO_PERMISSION.
test:
  description: >
    Point-in-time assertion after any SpiceDB migration: sentinel has no permissions.
    Wire as a blocking step in the AuthzProvisioning CI job.
    After Wave 3: also verify that manually writing a sentinel tuple and then checking
    still returns PERMISSIONSHIP_NO_PERMISSION (caveat enforcement).
  command: |
    zed permission check \
      --subject user:00000000-0000-0000-0000-000000000000 \
      --permission view_tree \
      --resource risk_tree:<any-id>
    # Expected: "false" / PERMISSIONSHIP_NO_PERMISSION.
sentinel_design_rationale:
  value: "00000000-0000-0000-0000-000000000000"
  reasons:
    visually_recognisable: >
      Immediately identifiable in logs as a placeholder — not a real user identity.
    keycloak_impossible: >
      Keycloak generates sub claims via UUID v4 (cryptographically random).
      Probability of producing all-zeros: 2^-122. Not a policy constraint — a
      mathematical property of UUID v4 generation. No issued JWT can carry this value.
    single_source_of_truth: >
      UserContextExtractor.AnonymousSentinelUuid is the only definition in the codebase.
      Auditable by grep across provisioning scripts, Helm charts, schema files, CI checks.
related_code:
  - "modules/server/src/main/scala/.../auth/UserContextExtractor.scala (AnonymousSentinelUuid, logStartupMode)"
  - "modules/common/src/test/.../domain/data/iron/AuthTypesSpec.scala (sentinel stability pin tests)"
  - "modules/server/src/test/.../auth/UserContextExtractorSpec.scala (noOp/requirePresent contracts)"
```

---

## Appendix: T4 Sentinel Threat Model — Extended Analysis

> **Status:** Design note. The CEL caveat is retained as defence-in-depth regardless of the
> threat analysis below. The YAML entry above is the authoritative machine-readable record.

### Why `00000000-0000-0000-0000-000000000000`?

The all-zeros UUID was chosen deliberately:

- **Visually recognisable in logs.** Any operator seeing this UUID immediately knows it is a
  placeholder, not a real user identity. It stands out from a UUID v4 at a glance.

- **Structurally impossible from Keycloak.** Keycloak generates `sub` claims using UUID v4 via a
  cryptographically random algorithm. The probability of randomly producing all-zeros is
  $2^{-122}$ — effectively impossible. This is not a policy constraint; it is a mathematical
  property of UUID v4 generation. No issued JWT can ever carry this value as a legitimate sub claim.

- **Single agreed-upon constant.** `UserContextExtractor.AnonymousSentinelUuid` is the single
  source of truth in the codebase. SpiceDB provisioning CI and the CEL caveat both reference
  this string, making it auditable by grep across provisioning scripts, Helm charts, and schema
  files.

### When does sentinel pollution actually matter?

The threat was originally stated as: "if the sentinel UUID is granted permissions in SpiceDB,
`noOp` mode silently carries authorization power into fine-grained mode." This is narrower than
it appears. Walk through each mode:

**Layer 0 — `noOp` + `AuthorizationServiceNoOp`**

`NoOp.check()` returns `ZIO.unit` immediately. It never calls SpiceDB at all. A polluted SpiceDB
instance has zero effect. Pollution cannot propagate by definition.

**Layer 1+2 — `requirePresent` + `AuthorizationServiceSpiceDB`**

The sentinel UUID can only reach SpiceDB as a check argument if `x-user-id: 00000000-...`
arrives in the HTTP request **and** passes through to the application. For this to happen with
Layers 1+2 active, all of the following must fail simultaneously:

1. **T1 — Mesh bypass:** attacker reaches the pod directly, bypassing the Istio waypoint
2. **T3 — Header stripping absent:** attacker-supplied `x-user-id` is forwarded rather than
   stripped before JWT claim injection
3. **Layer 1/OPA:** OPA allows the request despite no valid JWT

Each failure is independently detectable. Their simultaneous occurrence represents a catastrophic
infrastructure compromise, not a routine misconfiguration.

**The real T4 risk window**

Mode transition testing. When migrating from `noOp` to `fine-grained`, engineers may write test
tuples — including sentinel entries — to verify the provisioning tooling. If those tuples are not
cleaned up before the live switch, *and* if T1 and T3 also fail, the sentinel carries real
permissions. The CI check and Wave 3 CEL caveat close this window.

**Conclusion:** T4 is defence-in-depth for a narrow transition-period scenario. It is not a
steady-state threat in a correctly configured L1+L2 environment.

### Sentinel pollution vs. mode mismatch — two distinct concerns

These are often conflated but are orthogonal:

| Scenario | Sentinel pollution dangerous? | Mode mismatch dangerous? |
|----------|------------------------------|--------------------------|
| L0 with NoOp (correct) | No — NoOp never calls SpiceDB | No — intended |
| L1+L2 with SpiceDB (correct) | No — JWT randomness prevents sentinel from arriving | No — intended |
| L1+L2 but NoOp accidentally wired | No — NoOp never calls SpiceDB, pollution irrelevant | **Yes — all authz bypassed** |

Mode mismatch (NoOp wired when SpiceDB should be) is the more operationally realistic risk.
Mitigations:

- **Startup log** — `auth.extractor=noOp auth.sentinel.active=true` emitted at startup.
  Production namespaces should alert on this field.
- **Deploy-time gate** — switching from `noOp` to `requirePresent` requires a code change and
  redeploy, not just a configuration value drift.
- **`AuthConfig` consistency assertion (TODO)** — the application should assert at startup that
  the declared `auth.mode` matches the wired `UserContextExtractor` and `AuthorizationService`,
  failing fast rather than silently degrading.

### Why the CEL caveat is kept despite the narrow threat window

1. **It is cheap.** A CEL caveat evaluated on every SpiceDB check call has negligible overhead
   and zero application-code impact.

2. **It makes the constraint structural.** Rather than relying on the CI check (point-in-time),
   the caveat enforces "sentinel never has permissions" on every check, regardless of what is
   stored. A mistakenly written sentinel tuple is silently ignored by the SpiceDB engine.

3. **It future-proofs provisioning tooling.** As the SpiceDB schema and relationship write paths
   evolve across Waves 3–5, the caveat ensures no new write path can accidentally grant the
   sentinel access — even if a developer writes a migration that doesn't know about the sentinel.

4. **It is auditable.** The caveat value `"00000000-0000-0000-0000-000000000000"` is a literal in
   `infra/spicedb/schema.zed`, checkable by grep and code review.

The CEL caveat (Wave 3 blocker) is motivated not by threat severity alone but by removing an
entire class of provisioning bugs from the possible mistake space.
