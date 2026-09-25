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
Excluded by decision: `(`, `)`, `&` — present in the enterprise demo fixtures but not required; excluded per "no character not strictly needed" rule. All existing uses will be replaced in Step 4 (see replacements listed there).

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

Replacements that would change a name must be applied at **every reference site** atomically:
- `parentName` fields in the same fixture that reference the old name
- FOL query string literals in the same file that embed the old name as a quoted constant
  (e.g. `descendant_of(x, "Technology & Cyber")` → `descendant_of(x, "Technology and Cyber")`)
  Note: `&` is not special in the FOL lexer inside quoted strings — the update is purely
  a data-consistency requirement, not a syntax fix.

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

### Step 5 — Regression test: injection-shaped quoted literal is rejected at parse/bind level

Add a test to `BinderIntegrationSpec` (alongside B1/B2/B3) that verifies a query
string where a quoted constant terminates early and is followed by embedded FOL
operators is rejected before any evaluation.

The canonical attack form: a query whose quoted literal contains `"` to close the
string early, with additional FOL content appended — simulating what would happen
if node names were ever interpolated into query strings (they are not, but this
documents the structural guarantee):

```
// B4 — injection-shaped quoted literal
// The closing " after "IT Risk terminates the string; the characters that follow
// are injected FOL content.  The parser must reject this as malformed.
// Concrete form (adjust delimiters to whatever the FOL grammar accepts):
//   leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 0)")
// or any query string where a " appears mid-literal and the remainder parses as
// a second FOL expression.
```

The test must:
1. Call `VagueSemantics.evaluateTyped` (or the equivalent bind step) with the
   injection-shaped query string.
2. Assert the result is `Left(...)` — rejected, not evaluated.
3. Assert the satisfied/result output does NOT contain any value derivable from
   the injected expression (e.g. `gt_loss(p95(x), 0)` never fires).

**If the assertion at step 2 or 3 fails** — i.e. the injection-shaped query
returns `Right(...)` or produces an evaluation result — **stop immediately and
consult the user**. Do not attempt to work around or explain the deviation.
This indicates an unexpected parser behaviour that must be understood before
proceeding.

### Step 6 — Compile and test

```
sbt commonJVM/compile commonJS/compile server/compile server/test serverIt/test app/compile app/test
```

All modules must be green before marking done.

### Step 7 — Code quality review

Load `code-quality-review` skill and run the full checklist against all changed files.

---

## Completion criteria

- [ ] `SafeNameConstraint` type defined and documented
- [ ] `SafeName` backing type is `SafeNameStr` (not `SafeShortStr`)
- [ ] `ValidEmail` regex tightened to whitelist
- [ ] `refineName` error message names the allowed character set
- [ ] All fixture names containing `(`, `)`, `&` replaced consistently
- [ ] `parentName` cross-references and FOL query string literals updated atomically with name replacements
- [ ] B4 regression test added to `BinderIntegrationSpec`; asserts injection-shaped query string is rejected
- [ ] `commonJVM/compile`, `commonJS/compile`, `server/test`, `serverIt/test`, `app/test` green
- [ ] No test assertions weakened
- [ ] Code quality review passed

---

## Decision triggers during implementation

Stop and raise ⚠️ Decision Required if:
- A fixture name with a forbidden char is used as an external API contract (e.g. in an integration test that hits a live Irmin instance with that exact string stored)
- Any Tapir endpoint schema is affected (Decision Trigger #1)
- A stored name in the DB/Irmin would fail the new constraint on decode (migration concern)
