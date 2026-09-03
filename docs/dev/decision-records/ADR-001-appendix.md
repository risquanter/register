# ADR-001 Appendix: Output-Boundary Constraints, Checklist, and Red Flags

**Parent:** [ADR-001](ADR-001.md)

This appendix carries the output-boundary credential rules and the operational
checklists that support ADR-001's core decisions. The parent ADR states the
validation strategy; this document holds the longer-form material that would push
the ADR past its size target.

---

## External-System Output Boundary Constraints

Some values are not user input — they are config-loaded credentials or tokens that
are sent **verbatim** to external services as HTTP header field values (e.g.
`Authorization: Bearer <token>`). These values require a different Iron constraint
than values stored in the database or displayed in the UI.

### Rule

Apply `PrintableAscii` (range `0x21–0x7E`) to any string that will be:

- sent verbatim as an HTTP header field value, OR
- passed as a pre-shared key or bearer token to an external service API

This constraint blocks CRLF injection (`\r\n`), null bytes (`\x00`), and all ASCII
control characters — preventing the value from splitting an HTTP response or
injecting additional headers at the transport layer.

### Decision table

| Use case | Constraint |
|---|---|
| Config-loaded API token sent as `Authorization: Bearer` | `PrintableAscii` ✓ |
| Config-loaded URL for external HTTPS service | `SecureUrlConstraint` ✓ |
| User-supplied name or label | do NOT use `PrintableAscii` (spaces, accents, Unicode are valid) |
| Email address | do NOT use `PrintableAscii` (uses its own whitelist) |
| Value stored in DB via parameterised query | do NOT use `PrintableAscii` |
| Value used only within the JVM (never sent over HTTP) | do NOT use `PrintableAscii` |

### Named type aliases

```scala
// Blocks CRLF injection in HTTP header values.
// 0x21 (!) through 0x7E (~) — visible US-ASCII only; no space, no control chars.
type PrintableAscii = Match["^[\\x21-\\x7E]+$"]

// HTTPS-only URL — prevents silent plaintext downgrade for external service endpoints.
type SecureUrlConstraint = Not[Blank] & MaxLength[200] &
  Match["^https://(?:\\[[0-9a-fA-F:]+\\]|[^/:#?\\s]+)(?::\\d+)?(?:/[^\\s]*)?$"]

// Generic raw type for config-loaded credentials sent as HTTP Bearer tokens.
// PrintableAscii blocks CRLF injection; MaxLength[2048] covers any realistic JWT.
type ExternalTokenStr = String :| (Not[Blank] & MaxLength[2048] & PrintableAscii)
```

### Credential class pattern

Wrap `ExternalTokenStr` in a `final class` credential type following ADR-022 R1–R8
(redacted `toString`, explicit `reveal`, no `copy`/`unapply`, manual
`equals`/`hashCode`). Use `ValidationUtil.refineExternalToken` in the `fromString`
constructor. Do not add a JSON codec — config-only types are never serialised to or
from JSON.

```scala
// ✅ GOOD — PrintableAscii applied at Iron type level; credential class wraps the proof
final class SpiceDbToken private (private val raw: ExternalTokenStr):
  def reveal: String = raw
  override def toString: String = "SpiceDbToken(***)"

object SpiceDbToken:
  def fromString(s: String, fieldPath: String = "token"): Either[List[ValidationError], SpiceDbToken] =
    ValidationUtil.refineExternalToken(s, fieldPath).map(new SpiceDbToken(_))

// ❌ BAD — raw String reaches HTTP header; CRLF injection is possible
def callExternalService(token: String): Task[Response] =
  makeRequest(s"Authorization: Bearer $token")
```

### Rationale

- Injection guard is at the **type boundary** (config load), not the HTTP call
  site. Once a value is an `ExternalTokenStr`, it is statically known to be safe
  for headers.
- `PrintableAscii` covers all realistic token formats: base64, base64url, JWT
  (`<base64url>.<base64url>.<base64url>`), hex strings, SpiceDB pre-shared keys.
- Space (`0x20`) is excluded: HTTP/1.1 header fields use SP as a list separator. No
  realistic token format requires a literal space character.

### Cross-references

- **ADR-022**: credential class design (R1–R8) — `final class`, `private val`,
  `reveal`, redacted `toString`, no JSON codec for config-only types
- **ADR-001 §4**: URL refinement for internal service endpoints (`UrlConstraint`,
  http/https)
- `ValidationUtil.refineExternalToken` / `ValidationUtil.refineSecureUrl` —
  validation helpers

---

## Executable Validation Checklist

Before completing any PR or implementation phase, run these commands to verify
compliance:

```bash
# 1. Check for raw String domain IDs in Map/Set types (should return 0 matches outside tests)
grep -rn "Map\[String" modules/server/src/main/scala --include="*.scala" | grep -v "Config\|Json\|Env"

# 2. Check for String keys in cache/index structures
grep -rn "cache.*String\|index.*String" modules/server/src/main/scala --include="*.scala"

# 3. Verify all service methods use Iron types (look for raw primitives)
grep -rn "def.*nodeId: String\|def.*id: String\|def.*id: Long" modules/server/src/main/scala/com/risquanter/register/services --include="*.scala"

# 4. Check for .refineUnsafe usage (should be minimal, only in test helpers)
grep -rn "refineUnsafe" modules/server/src --include="*.scala"
```

Each command should return **zero results** in production code (main/scala). Any
matches require review.

---

## Red Flags (Stop and Verify)

If you observe any of these patterns while implementing, **STOP and verify against
ADR-001**:

| Pattern | Concern | Correct Approach |
|---|---|---|
| `Map[String, DomainObject]` | Raw String as domain ID key | Use `Map[NodeId, DomainObject]` with NodeId = SafeId.SafeId |
| `def method(id: String)` in service | Raw String parameter in service signature | Use refined type: `def method(id: SafeId.SafeId)` |
| `node.id` for lookups | Extracting String from domain object | Use `node.safeId` to preserve type |
| `cache.get(id.toString)` | Converting Iron type to String for lookup | Use Iron type directly as key |
| `.refineUnsafe` in production code | Bypassing validation | Use `fromString(...).getOrElse(...)` or smart constructors |
| `String :| Constraint` in new code | Defining inline refined type | Use existing type aliases (NodeId, SafeId.SafeId) |

**When in doubt:** If a value represents a domain entity identifier, it should use
the same Iron type as the domain model.
