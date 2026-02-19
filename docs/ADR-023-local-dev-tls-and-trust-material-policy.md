# ADR-023: Local Development TLS and Trust Material Policy

**Status:** Proposed  
**Date:** 2026-02-19  
**Tags:** security, tls, local-dev, kubernetes, operations

---

## Context

- Local development should resemble production security posture where practical.
- TLS should be enabled by default in cluster-facing paths to catch auth/cookie/redirect issues early.
- Trust material must not leak through source control.
- Teams need a reproducible local setup without committing CA private keys or long-lived secrets.

---

## Decision

### 1. TLS-by-default for local cluster ingress

All local cluster entry points are configured for HTTPS first; HTTP is redirect-only or explicitly marked local-only.

```yaml
# Conceptual ingress pattern
spec:
  tls:
    - hosts: ["register.local"]
      secretName: register-local-tls
```

### 2. No CA private keys in repository

Only public certs or generated artifacts that are non-sensitive may be referenced. Private keys are generated locally and stored outside git.

```gitignore
# local trust artifacts
/dev/certs/*.key
/dev/certs/*-ca.pem
/dev/certs/*-ca-key.pem
```

### 3. Documented local trust bootstrap, not bundled CA infrastructure

Repository provides setup instructions (e.g., mkcert/local CA hints), but does not ship CA bootstrap automation that distributes private trust anchors.

```markdown
# Local trust setup (example)
1. Install mkcert
2. Generate cert for register.local
3. Create Kubernetes secret from generated cert/key
```

### 4. Explicit local-only security exceptions

Any non-production-safe setting (e.g., `sslmode=disable`, insecure endpoint) must be clearly labeled and isolated to local overlays.

```hocon
register.security.local {
  allow-insecure-http = true  # LOCAL DEV ONLY
}
```

### 5. Service datastore separation on shared PostgreSQL instance

When sharing one PostgreSQL server in local dev, each service gets its own database and least-privilege role.

```sql
CREATE DATABASE keycloak;
CREATE DATABASE register_app;
CREATE DATABASE spicedb;

CREATE ROLE keycloak_user LOGIN PASSWORD '...';
CREATE ROLE register_app_user LOGIN PASSWORD '...';
CREATE ROLE spicedb_user LOGIN PASSWORD '...';
```

---

## Code Smells

### ❌ Committed trust private keys

```text
# BAD
certs/local-ca.key committed to git
```

```text
# GOOD
private keys generated per developer machine; excluded via .gitignore
```

### ❌ HTTP-first local setup

```yaml
# BAD
spec:
  rules:
    - host: register.local
      http: ...
```

```yaml
# GOOD
spec:
  tls:
    - hosts: ["register.local"]
      secretName: register-local-tls
```

### ❌ Shared PostgreSQL superuser credentials across services

```hocon
# BAD
db.user = "postgres"
db.password = "same-for-every-service"
```

```hocon
# GOOD
register.db.user = "register_app_user"
keycloak.db.user = "keycloak_user"
spicedb.db.user = "spicedb_user"
```

### ❌ Undocumented insecure local flags

```hocon
# BAD
sslmode = disable
```

```hocon
# GOOD
sslmode = disable  # LOCAL DEV ONLY; forbidden in staging/prod
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `docs/AUTHORIZATION-PLAN.md` | TLS-by-default local cluster posture + security notes |
| `docs/DOCKER-DEVELOPMENT.md` (or new ops doc) | Local trust bootstrap instructions (mkcert/local CA hints) |
| Kubernetes overlays/charts | HTTPS ingress and local-only exception overlays |
| Secret management docs | No private trust material in repo; secret injection workflow |
| PostgreSQL provisioning scripts/charts | Per-service DB and least-privilege roles |

---

## References

- [ADR-012: Service Mesh Strategy](./ADR-012.md)
- [ADR-022: Secret Handling](./ADR-022-secret-handling.md)
- [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md)
