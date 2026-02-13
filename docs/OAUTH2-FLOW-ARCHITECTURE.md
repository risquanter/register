# OAuth2 Flow Architecture with Istio Ambient Mode

**Related ADR:** [ADR-012: Service Mesh Strategy](./ADR-012.md)  
**Date:** 2026-01-17

---

## Overview

This document details the OAuth2 authentication and authorization flow in a zero-trust architecture using:

- **Istio Ambient Mode** — Service mesh (ztunnel for L4 mTLS, waypoint proxies for L7/JWT)
- **Keycloak** — OAuth2/OIDC identity provider
- **OPA (Open Policy Agent)** — Authorization via Rego policies

---

## System Bootstrap

Before runtime, the following must be configured:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Bootstrap Phase (Pre-Deploy)                      │
└─────────────────────────────────────────────────────────────────────────┘

1. Keycloak Configuration
   ├── Create realm: "register"
   ├── Create client: "register-api" (confidential, service account enabled)
   ├── Create client: "register-web" (public, PKCE flow)
   ├── Define roles: admin, analyst, viewer
   ├── Configure mappers: include roles in JWT claims
   └── Export public key for JWT validation

2. OPA Policy Bundle
   ├── Define Rego policies:
   │   ├── allow_read.rego   → viewer, analyst, admin
   │   ├── allow_write.rego  → analyst, admin
   │   └── allow_admin.rego  → admin only
   └── Deploy to OPA bundle server (or ConfigMap)

3. Istio Configuration
   ├── RequestAuthentication (validate JWT from Keycloak)
   ├── AuthorizationPolicy (ext_authz to OPA)
   ├── PeerAuthentication (mTLS STRICT)
   └── Waypoint proxy for L7 policies
```

---

## Runtime Flow: User Login → API Call → Service Chain

### Complete Sequence

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Runtime Request Flow                             │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────┐     ┌──────────────┐     ┌─────────────┐
│  Browser │     │   Keycloak   │     │ Istio Ingress│
│   (SPA)  │     │    (OIDC)    │     │   Gateway    │
└────┬─────┘     └──────┬───────┘     └──────┬───────┘
     │                  │                     │
     │ 1. User clicks   │                     │
     │    "Login"       │                     │
     │─────────────────▶│                     │
     │                  │                     │
     │ 2. Redirect to   │                     │
     │    Keycloak      │                     │
     │◀─────────────────│                     │
     │                  │                     │
     │ 3. User enters   │                     │
     │    credentials   │                     │
     │─────────────────▶│                     │
     │                  │                     │
     │ 4. Keycloak      │                     │
     │    validates,    │                     │
     │    issues JWT    │                     │
     │◀─────────────────│                     │
     │                  │                     │
     │ 5. SPA stores    │                     │
     │    JWT (memory)  │                     │
     │                  │                     │
     │ 6. API call with │                     │
     │    Authorization:│                     │
     │    Bearer <JWT>  │                     │
     │────────────────────────────────────────▶│
     │                  │                     │
     │                  │     7. Istio        │
     │                  │        validates    │
     │                  │        JWT          │
     │                  │        signature    │
     │                  │                     │
     │                  │     8. Extract      │
     │                  │        claims,      │
     │                  │        forward to   │
     │                  │        OPA          │
     │                  │                     │
```

### OPA Authorization & Service Chain

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    OPA Authorization → Service Mesh                      │
└─────────────────────────────────────────────────────────────────────────┘

     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
     │ Istio Ingress│     │     OPA     │     │   Waypoint  │
     │   Gateway    │     │  (ext_authz)│     │    Proxy    │
     └──────┬───────┘     └──────┬──────┘     └──────┬──────┘
            │                    │                    │
            │ 8. POST /v1/data/  │                    │
            │    authz/allow     │                    │
            │    {input: {...}}  │                    │
            │───────────────────▶│                    │
            │                    │                    │
            │                    │ 9. Evaluate        │
            │                    │    Rego policy     │
            │                    │    against claims  │
            │                    │                    │
            │ 10. {allow: true}  │                    │
            │◀───────────────────│                    │
            │                    │                    │
            │ 11. Forward to     │                    │
            │     waypoint       │                    │
            │─────────────────────────────────────────▶
            │                    │                    │
            │                    │      12. L7        │
            │                    │          routing,  │
            │                    │          headers   │
            │                    │          injection │


     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
     │   Waypoint   │     │ ZIO Backend │     │    Irmin    │
     │    Proxy     │     │  (ztunnel)  │     │  (ztunnel)  │
     └──────┬───────┘     └──────┬──────┘     └──────┬──────┘
            │                    │                    │
            │ 13. mTLS to        │                    │
            │     ztunnel        │                    │
            │───────────────────▶│                    │
            │                    │                    │
            │                    │ 14. ZIO receives   │
            │                    │     pre-validated  │
            │                    │     request with   │
            │                    │     claims in      │
            │                    │     headers        │
            │                    │                    │
            │                    │ 15. Business logic │
            │                    │     (parse claims, │
            │                    │     data-level     │
            │                    │     authz, audit)  │
            │                    │                    │
            │                    │ 16. mTLS to Irmin  │
            │                    │─────────────────────▶
            │                    │                    │
            │                    │ 17. Irmin query    │
            │                    │◀─────────────────────
            │                    │                    │
            │ 18. Response       │                    │
            │◀───────────────────│                    │
```

---

## What the Service Mesh Handles

| Concern | Handler | Mechanism |
|---------|---------|-----------|
| JWT signature validation | Istio `RequestAuthentication` | Keycloak public key (JWKS) |
| JWT expiry check | Istio | `exp` claim |
| Issuer/audience validation | Istio | `iss`, `aud` claims |
| Role-based access control | OPA via `ext_authz` | Rego policies |
| mTLS between services | ztunnel | Automatic, zero-config |
| Retries / circuit breaking | Istio | `VirtualService`, `DestinationRule` |
| Rate limiting | Istio | EnvoyFilter or waypoint config |

---

## What Remains in Service Code

| Concern | Why Not in Mesh | Service Responsibility |
|---------|-----------------|------------------------|
| JWT claims parsing | Mesh validates, doesn't parse for app | Extract `UserContext` from headers |
| Data-level authorization | Mesh doesn't know domain model | "Can user X access resource Y?" |
| Audit logging | Application-specific semantics | Log who did what, when |
| Token refresh (SPA) | Client-side concern | Refresh before expiry |

### Estimated Service Code

- **JWT claims parsing:** ~50-80 lines (one-time middleware)
- **Data-level authz:** ~30-50 lines (per domain check)
- **Audit logging:** ~20-30 lines (structured logging helper)

**Total:** ~100-200 lines across the entire backend

---

## Development Environment

**Decision:** Let it fail (no service mesh locally)

- Docker Compose runs services directly (no sidecars)
- No JWT validation locally (trust all requests or mock)
- Failures are acceptable during development
- Integration testing with mesh happens in staging/CI

---

## Reference Implementation

For initial implementation guidance:

- **Keycloak:** Consult Keycloak OIDC documentation and JS adapter docs for OAuth2/OIDC integration
- **Istio JWT:** Consult Istio RequestAuthentication docs for claims extraction via `x-jwt-claims` header
- **ZIO + PostgreSQL patterns:** Consult Cheleb reference architecture (relevant for persistence layer patterns only)

Validate applicability against current implementation state before adopting patterns.

---

## Istio Configuration Examples

### RequestAuthentication (JWT Validation)

```yaml
apiVersion: security.istio.io/v1
kind: RequestAuthentication
metadata:
  name: keycloak-jwt
  namespace: register
spec:
  jwtRules:
  - issuer: "https://keycloak.example.com/realms/register"
    jwksUri: "https://keycloak.example.com/realms/register/protocol/openid-connect/certs"
    audiences:
    - "register-api"
    forwardOriginalToken: true
```

### AuthorizationPolicy (OPA ext_authz)

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: opa-authz
  namespace: register
spec:
  action: CUSTOM
  provider:
    name: opa
  rules:
  - to:
    - operation:
        paths: ["/api/*"]
```

### PeerAuthentication (mTLS)

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: mtls-strict
  namespace: register
spec:
  mtls:
    mode: STRICT
```

---

## Security Notes

1. **Zero-trust:** Every hop validates identity (JWT at ingress, mTLS between services)
2. **Defense in depth:** OPA provides additional layer beyond JWT roles
3. **No secrets in code:** JWT validation uses JWKS endpoint, not embedded keys
4. **Token storage:** SPA stores JWT in memory only (not localStorage)
5. **Audit trail:** All authorization decisions logged for compliance

---

*This document is maintained alongside ADR-012. Update when auth architecture evolves.*
