# Plan: Input Type Hardening — SafeName, ValidEmail

**Status:** APPROVED — ready for implementation  
**Decision thread:** conversation 2026-06-11  
**Rationale:** `SafeName` enforces only length and non-blank — no character-set restriction.
`ValidEmail` enforces only presence of `@` — any injection string with an `@` passes.
Both types name safety they do not deliver. This plan tightens both to whitelist-only
character sets, surveys all affected fixtures, and updates them consistently.

`ValidUrl` / `UrlConstraint` — assessed adequate (http/https scheme anchoring rules
out javascript:, data:, file: vectors; path `[^\s]*` is broad but necessary). No change.

---

## Approved constraints

### SafeName
```
type SafeNameConstraint = Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9 /\\-]+$"]
```
Allowed: letters, digits, space, `/`, `-`  
Excluded by decision: `(`, `)`, `&` — not present in any existing fixtures or demos; no special injection risk but excluded per "no character not already used" rule.

### ValidEmail
```
type ValidEmail = String :| (Not[Blank] & MaxLength[50] &
  Match["^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"])
```
Tightened from `[^@]+@[^@]+` which accepted `<script>@x.com` and SQL injection strings.

---

## Scope

| # | File | Change |
|---|------|--------|
| 1 | `modules/common/src/main/scala/.../iron/OpaqueTypes.scala` | Add `type SafeNameConstraint`; change `SafeShortStr` backing of `SafeName` to new constraint type; tighten `ValidEmail` regex |
| 2 | `modules/common/src/main/scala/.../iron/ValidationUtil.scala` | Update `refineName` error message to name the character whitelist |
| 3 | `modules/common/src/main/scala/.../iron/ValidationMessages.scala` | Add or update INVALID_CHARACTERS message for name |
| 4 | All Scala test fixtures | Survey and replace any name fixture containing `(`, `)`, `&` |
| 5 | `examples/*.sh` | Survey and replace any name value containing `(`, `)`, `&` |
| 6 | `examples/*.httpie` / `examples/*.http` | Survey and replace |
| 7 | `tests/bats/**` | Survey and replace |
| 8 | Any JSON seed files or migration data | Survey and replace |

---

## Implementation steps

### Step 1 — Survey (do this before any edit)

Run each of the following and record all matches. Do not edit yet.

```bash
# Names with forbidden chars in Scala sources
grep -rn 'name.*[()&]' modules/ --include="*.scala" | grep -v '^\s*//'

# Names with forbidden chars in shell/httpie examples
grep -rh '"name".*[()&]' examples/ tests/bats/

# Names with forbidden chars in JSON files
grep -rn '"name".*[()&]' . --include="*.json" --include="*.sh" --include="*.http"
```

For each match: record file, line, current value, proposed replacement.
Replacements must preserve meaning:
- `(PII)` → remove parentheses: `PII` or rephrase as `- PII`
- `Third-Party & Supply Chain` → `Third-Party and Supply Chain`
- `Compliance & Legal Risk` → `Compliance and Legal Risk`

Replacements that would change a name used as a foreign key reference (e.g. `parentName`)
**must be updated at every reference site in the same file** atomically.

### Step 2 — Update Iron types

Edit `OpaqueTypes.scala`:
1. Add above `object SafeName`:
   ```scala
   // SafeName character whitelist: letters, digits, space, hyphen, forward-slash.
   // Excludes all HTML/script injection chars. Deliberately narrow — add chars only
   // when a concrete use case requires them and no injection risk exists.
   type SafeNameConstraint = Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9 /\\-]+$"]
   type SafeNameStr = String :| SafeNameConstraint
   ```
2. Change `opaque type SafeName = SafeShortStr` → `opaque type SafeName = SafeNameStr`
3. Change `def apply(s: SafeShortStr)` → `def apply(s: SafeNameStr)`
4. Change `def unapply(sn: SafeName): Option[SafeShortStr]` → `Option[SafeNameStr]`
5. Change `def value: SafeShortStr` → `def value: SafeNameStr`
6. Tighten `ValidEmail` regex as above.

`SafeShortStr` stays unchanged — it is a general-purpose alias used elsewhere.

### Step 3 — Update ValidationUtil

`refineName`: change `.refineEither` target type to `SafeNameConstraint` (or via `SafeNameStr`).
Update the error message to: `"Name must be 1–50 characters using only letters, digits, spaces, hyphens, and forward slashes"`.

### Step 4 — Update fixture files

Apply all replacements identified in Step 1. For each `.sh` or `.http` file, verify
`parentName` references are updated to match wherever the name is used as a key.

### Step 5 — Compile and test

```
sbt commonJVM/compile commonJS/compile server/compile server/test app/compile app/test
```

All modules must be green before marking done.

### Step 6 — Code quality review

Load `code-quality-review` skill and run the full checklist against all changed files.

---

## Completion criteria

- [ ] `SafeNameConstraint` type defined and documented
- [ ] `SafeName` backing type is `SafeNameStr` (not `SafeShortStr`)
- [ ] `ValidEmail` regex tightened to whitelist
- [ ] `refineName` error message names the allowed character set
- [ ] All fixture names containing `(`, `)`, `&` replaced consistently
- [ ] `parentName` cross-references updated atomically with name replacements
- [ ] `commonJVM/compile`, `commonJS/compile`, `server/test`, `app/test` green
- [ ] No test assertions weakened
- [ ] Code quality review passed

---

## Decision triggers during implementation

Stop and raise ⚠️ Decision Required if:
- A fixture name with a forbidden char is used as an external API contract (e.g. in an integration test that hits a live Irmin instance with that exact string stored)
- Any Tapir endpoint schema is affected (Decision Trigger #1)
- A stored name in the DB/Irmin would fail the new constraint on decode (migration concern)
