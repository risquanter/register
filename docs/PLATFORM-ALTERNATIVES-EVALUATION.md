# Alternative Platform Evaluation — Service Mesh and Orchestration

**Date:** March 2026  
**Status:** Closed — alternatives rejected, current stack retained  
**Related:** [ADR-012](./ADR-012.md) (Service Mesh Strategy), [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md), [THREAT-CATALOG.md](./THREAT-CATALOG.md)

---

## Terminology

| Term | Meaning |
|---|---|
| **Orchestrator** | Software that schedules and manages the lifecycle of containerised workloads (start, stop, restart, placement) |
| **Service mesh** | Infrastructure layer that handles service-to-service communication: encryption (mTLS), traffic policy, and observability — transparently and without application code changes |
| **CNI** | Container Network Interface — the plugin that implements networking between pods/containers. Cilium is the CNI used here |
| **eBPF** | Extended Berkeley Packet Filter — a Linux kernel technology that lets programs run safely in kernel space. Cilium uses eBPF to enforce network policy at the kernel level rather than in a process that could be bypassed |
| **mTLS** | Mutual TLS — both sides of a connection present certificates and verify each other. Prevents traffic interception and impersonation inside the cluster |
| **SPIFFE/SVID** | Secure Production Identity Framework for Everyone / SPIFFE Verifiable Identity Document — a standard for cryptographic workload identity. Each pod gets an X.509 certificate proving "I am service X" without sharing secrets |
| **JWKS** | JSON Web Key Set — the public key endpoint that JWT validators use to verify token signatures. Istio fetches this from Keycloak at startup and caches it, so JWT validation happens in the proxy with no Keycloak round-trip per request |
| **ext_authz** | Envoy's External Authorization filter — a gRPC protocol that lets Envoy delegate an authorization decision to an external service (OPA in this case) before forwarding a request. The result is fail-closed: if the external service is unreachable, the request is denied |
| **HCL** | HashiCorp Configuration Language — the configuration syntax used by Nomad, Consul, Terraform, and Vault. Similar role to YAML in Kubernetes — used to describe jobs, services, and infrastructure resources |
| **CRD** | Custom Resource Definition — Kubernetes extension mechanism that allows new resource types (like `RequestAuthentication` or `EnvoyFilter`) to be treated the same as built-in Kubernetes objects: version-controlled, applied with `kubectl apply`, monitored by ArgoCD for drift |
| **PDP** | Policy Decision Point — the component that evaluates an authorization query and returns allow/deny. OPA is a PDP |
| **PEP** | Policy Enforcement Point — the component that enforces the PDP's decision. The Istio waypoint proxy is the PEP |
| **PAP** | Policy Administration Point — the system through which policy is written and published. The Rego policy files in git (applied by ArgoCD) form the PAP for OPA |
| **Rego** | The declarative policy language used by OPA. Policies are written in Rego, evaluated against input (JWT claims, HTTP method, path), and return allow/deny |
| **Ambient mode** | Istio operating mode where no sidecar container is injected into application pods. Instead, a per-node `ztunnel` process handles mTLS at the kernel network namespace boundary, and a per-namespace `waypoint` proxy handles L7 policy |
| **Waypoint** | In Istio ambient mode, a dedicated Envoy proxy deployed per namespace that handles Layer 7 concerns: JWT validation, authorization policy, header manipulation, and OPA ext_authz calls |
| **ztunnel** | The Istio ambient DaemonSet (one instance per node) that handles Layer 4 mTLS (HBONE tunnel) between all enrolled pods, without touching L7 |

---

## Context

The security architecture for this application is defined by three non-negotiable invariants, derived from ADR-012 and the threat catalog:

| ID | Invariant | What it prevents |
|---|---|---|
| **T1** | All external traffic reaches the application exclusively via the waypoint proxy — no direct pod access | An attacker reaching the app pod directly and injecting forged identity headers |
| **T2** | JWT signature and expiry are validated before any application code runs | Expired or forged tokens being accepted; arbitrary identity injection |
| **T3** | Client-supplied `x-user-id`, `x-user-email`, and `x-user-roles` headers are stripped unconditionally at the proxy before JWT injection | A client forging `x-user-id: <victim-uuid>` without a valid JWT and having the application treat it as a mesh-asserted identity |

T1 and T3 together are what give the application code the right to read `x-user-id` from the request headers and trust it as a verified identity without parsing JWTs itself. This trust model is the core architectural decision (ADR-012, ADR-024). All alternatives were evaluated against these three invariants first.

The following alternatives were considered and rejected.

---

## Alternative A — Nomad + Consul + Envoy

### Description

This is the most mature Kubernetes alternative in the cloud-native space. The stack would be:

- **Nomad** (HashiCorp) as the container orchestrator — a single binary that schedules jobs and manages container lifecycle. Far lighter than Kubernetes (no etcd cluster, no kube-apiserver, no controller-manager)
- **Consul** (HashiCorp) for service discovery and the service mesh layer. Consul Connect provides service-to-service mTLS using Envoy sidecars and Consul-issued SPIFFE certificates
- **Envoy** as the data plane proxy, configured via Consul or via manually authored HCL/protobuf files
- **OPA** running with its Envoy gRPC plugin, wired as an `ext_authz` filter in the Envoy filter chain — functionally identical to the Kubernetes setup
- **Keycloak**, **SpiceDB**, and **PostgreSQL** running as Nomad jobs

Envoy natively supports both JWT validation (`envoy.filters.http.jwt_authn`) and unconditional header removal (`request_headers_to_remove`), so T2 and T3 are achievable in this stack. OPA's `ext_authz` gRPC protocol is Envoy-native and platform-agnostic — it works identically in Nomad and Kubernetes.

### Rejection reasons

**T1 — network enforcement is weaker.** Consul service mesh intentions are enforced by the Envoy sidecar process running inside the container environment, not by the kernel. Cilium (the current CNI) enforces network policy using eBPF at the kernel level. The practical difference: a container that is compromised and kills or bypasses its Envoy sidecar is outside Consul's policy enforcement. With Cilium eBPF rules, even a fully compromised container process cannot establish a TCP connection that violates network policy — the kernel drops the packet before it reaches any process. On a single trusted node this difference is unlikely to matter in practice, but it represents a real downgrade in the threat model for T1.

**Manual Envoy configuration replaces declarative CRDs.** In the Kubernetes stack, the EnvoyFilter that strips `x-user-id` is a YAML file in git, applied by ArgoCD, monitored for drift, and re-applied automatically if deleted. In the Nomad stack, the equivalent is a block of Envoy HCL filter chain configuration. It is less readable, not monitored for drift, and has no equivalent of ArgoCD's automatic self-healing. Any accidental deletion or misconfiguration of the header-stripping rule (critical for T3) would not be detected automatically.

**No declarative policy model for security configuration.** The current setup treats all security policy — Istio `RequestAuthentication`, `AuthorizationPolicy`, `EnvoyFilter`, Cilium `NetworkPolicy`, OPA Rego — as Kubernetes resources managed by ArgoCD. Every change is a git commit, every drift is detected and alerted. This property does not exist in the Nomad/Consul ecosystem without building it from scratch using Terraform and custom monitoring.

**Operational burden is higher, not lower.** Writing and maintaining Envoy HCL filter chains manually is significantly more error-prone than Kubernetes CRDs. The apparent simplicity of Nomad comes at the cost of shifting complexity into Envoy configuration that has no equivalent tooling for validation, linting, or automated testing.

---

## Alternative B — systemd + Traefik/Caddy + Manual Envoy Sidecars

### Description

The "no orchestrator" approach. Each service (application, Keycloak, PostgreSQL, SpiceDB, OPA, Envoy) runs as a `systemd` unit on the host. Traefik or Caddy handles ingress, TLS termination, and routing. Envoy sidecars are deployed manually per service (as additional systemd units) and wired with static configuration. OPA runs as a systemd service and is called via `ext_authz`.

T2 and T3 are achievable via Envoy. OPA works identically.

### Rejection reasons

**T1 — network enforcement is fragile by design.** Enforcing that no process except the Envoy sidecar can receive inbound traffic on the application's port requires `iptables`/`nftables` rules written and maintained manually. These rules are not declarative, not auditable in git, not self-healing, and can be silently broken by a system update, a firewalld reload, or an operator mistake. There is no central policy store that can be inspected to answer "is T1 currently enforced?". This is materially weaker than Cilium eBPF enforcement.

**No sidecar injection.** There is no automatic mechanism to ensure that an updated application pod has Envoy attached. Each deployment of a new application version requires manually re-wiring the Envoy sidecar. The EnvoyFilter that enforces T3 can be missing from a new deployment without automated detection.

**Operational cost is the highest of all options evaluated.** Rolling updates require scripting. Rollback requires running old binaries. Health checks, restart policies, and dependency ordering are all manual systemd configuration. The net engineering effort is significantly higher than maintaining a k3s cluster.

**The security model cannot be audited declaratively.** The entire value of the current GitOps approach — every security decision is a file in git, every drift is detected — is absent. Security properties exist only as running system state.

---

## Alternative C — k3s with Linkerd instead of Istio

### Description

Retain Kubernetes (k3s) and Cilium, but replace Istio with Linkerd, which is a lighter-weight service mesh. Linkerd uses a Rust-based micro-proxy injected as a sidecar into each pod. It provides mTLS between services and basic traffic management.

### Rejection reasons

**Linkerd does not support `ext_authz`.** There is no mechanism to wire OPA as an authorization filter within Linkerd. The OPA integration depends on Envoy's `ext_authz` gRPC protocol, which Linkerd's micro-proxy does not implement. Adding OPA to a Linkerd-based setup would require deploying a standalone Envoy proxy in front of the application — which means running Kubernetes + Linkerd + Envoy, adding a component rather than removing one.

**T2 and T3 are not natively supported.** Linkerd has no equivalent of Istio's `RequestAuthentication` CRD for JWT validation and no declarative header-stripping mechanism. Both T2 and T3 would have to be implemented in application code or in a separately deployed Envoy, negating the "application trusts mesh-injected headers" trust model that ADR-012 and ADR-024 depend on.

**Linkerd uses sidecar mode.** Istio ambient mode (currently deployed) has *lower* per-pod resource overhead than Linkerd sidecar mode, because ztunnel is a single per-node process rather than a container injected into every pod. Switching to Linkerd would increase per-pod overhead, not decrease it.

**Net result.** Replacing Istio with Linkerd produces a stack that requires adding Envoy anyway (for OPA), breaks the JWT validation model (T2), breaks the header-stripping model (T3), increases pod overhead, and retains all of the Kubernetes complexity. It is a strict regression.

---

## Conclusion

The three alternatives were rejected because they either weaken the security model (T1, T2, or T3), require significantly more manual engineering to achieve equivalent security properties, or introduce more components rather than fewer.

The root cause of the perceived complexity in the current stack is not Kubernetes itself — it is the security requirements. The three invariants (T1, T2, T3) require a proxy that enforces policy before the application sees traffic, kernel-level network enforcement that cannot be bypassed by a compromised process, and a declarative, auditable, self-healing policy model. These requirements exist regardless of orchestrator. Kubernetes + Istio ambient + Cilium is the most direct and well-supported path to satisfying all three simultaneously.

The highest-value complexity reduction available — without degrading the security model — is moving PostgreSQL and Keycloak to managed cloud services (e.g., Cloud SQL, Auth0, or a managed Keycloak instance). This eliminates the `infra` namespace and its operational concerns entirely while leaving the security architecture unchanged.
