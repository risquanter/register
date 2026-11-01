# PLAN — Semantic duplicates across the register codebase

Written 2026-11-01. This plan has one purpose: find every type, closed set and
rule in the codebase that restates an existing one under another name, have the
user rule on each, fix the ruled ones, and land the result green.

It is run in a fresh session by pasting the prompt in §5. The plan authorizes
the review only. No source file is edited until §6's continuation section is
written, approved, and given an inventory by the user.

## 1. Why

A semantic duplicate is a second definition of a concept the codebase already
defines, under a different name. Nothing fails when one is written. Both copies
compile, both pass their tests, and the two then drift apart.

Three were found in one plan section on 2026-11-01. `PLAN-ERROR-WIRE-BOUNDARY.md`
§17 proposed three field enums whose cases were the cases of the existing
`FieldName` (`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`).
It proposed a field-by-field merge rule that was the existing
`MergeConflictRule.isConflict` and `merged` applied to another value type. It
named the fork version `atFork`, where the existing code calls it `base`.

The rules that forbid this exist: the family search in
`.claude/rules/scala-algebraic-design.md`, and "**MUST-FIX** if it duplicates an
existing definition" in the `code-quality-review` skill §11. Both now also name
semantic duplicates explicitly. They bind new work. This plan reaches the code
written before they did.

## 2. Scope

Every Scala source under `src/main/scala` in all four modules:

- `modules/common/src/main/scala/`
- `modules/server/src/main/scala/`
- `modules/app/src/main/scala/`
- `modules/server-it/` contributes no main sources and is out of scope.

Test sources are out of scope as a source of findings. They are in scope as
files a fix must update.

## 3. What counts as a duplicate

Two definitions are a duplicate pair when they denote the same concept. The
spelling is evidence, not the test. Four shapes cover what has been seen:

1. **Closed sets.** Two enums, or an enum and a set of string literals, whose
   cases name the same domain fields or values.
2. **Records.** A case class whose fields are another case class's fields, or a
   subset of them, with no projection relation stated between the two.
3. **Rules.** Two functions applying the same rule, where one differs only in
   the value type or the parameter order.
4. **Names.** One concept spelled two ways in two places: `base` and `atFork`,
   `kind` and `type`.

Two concepts that share a spelling are not a pair. `RiskTreeRequests.NodeKind`
and `MergeConflictEntry.TagField = "kind"` share a word and nothing else.

A projection that exists because a type cannot cross a module boundary is not a
duplicate if its documentation names the type it projects. One that does not
name it is a finding of shape 2, and the fix is the documentation line.

## 4. Decisions

- **`SD-D-1`.** Findings are presented in decision-guide format per finding, or
  per group of findings sharing one fix. Each carries the cleanup-scope choice
  the project's rules require: fix every occurrence now, or route it elsewhere
  by an explicit ruling.
- **`SD-D-2`.** Every ruled fix lands in §6 of this plan, never in a new plan.
  The fixes are one epic and ship whole.

No other decision is open before the review runs.

## 5. The session prompt

Paste the block below into a new session started in `/home/danago/projects/register`.

```text
Run the semantic-duplicate review defined in docs/dev/plans/PLAN-SEMANTIC-DUPLICATES.md.

0. Before anything else, run the `reload` skill and print its proof list. Then
   read docs/dev/plans/PLAN-SEMANTIC-DUPLICATES.md in full. It is the plan this
   session works on. The approval token does not name it yet, and no source file
   under modules/ or build.sbt may be edited until §6 exists, is approved, and
   the user has created its inventory.

1. Mechanical listing. Write a Python script into the session scratchpad and
   run it by path with `python3 -I`. It reads every *.scala file under
   modules/{common,server,app}/src/main/scala and prints:
   a. every enum, with its fully qualified owner and its case names;
   b. every case class, with its field names and declared types;
   c. every method of every object and trait, with its parameter types and
      result type;
   d. every string literal that appears in a `match` arm or an equality test
      in two or more files.
   Then it prints candidate pairs:
   - enums sharing three or more case names, or sharing over half the cases of
     the smaller one (thresholds chosen in this plan, to keep two-case
     coincidences such as Main/Scenario out);
   - case classes where one's (name, type) fields are a subset of the other's,
     or where three or more (name, type) fields coincide;
   - methods in different owners with the same parameter-type list and result
     type once concrete types are replaced by a type variable;
   - case names and field names spelled differently for one concept, read off
     the lists by hand in step 2.
   The script's output is a candidate list, not a finding list.

2. Read every candidate pair in full: both definitions, their scaladoc, and
   their call sites. Use the Read tool for files and grep for call sites. Decide
   each pair against §3 of the plan, by meaning. Record the reason for every
   pair you drop, in one line.

3. Write docs/dev/reviews/REVIEW-semantic-duplicates.md. For each kept finding:
   the two definitions with file:line and the code quoted, the shape from §3,
   the concept both denote, every occurrence the fix would touch, and which
   definition should survive and why. List the dropped pairs with their
   one-line reasons at the end.

4. Present the findings to the user in plain English, in decision-guide format
   per §4 SD-D-1, numbered. Each decision guide states the cleanup-scope choice
   (fix every occurrence now, naming them, or route elsewhere). Then halt.
   Make no edits and no further tool calls until the user rules.

5. When the user rules, write §6 of the plan as a continuation section: for
   each ruled finding, the exact signatures after the fix as copy-pasteable
   Scala, every file it touches by full repo-relative path, the ADR review
   (the `adr-constraints` skill names the records), the terminology impact
   against docs/user/TERMINOLOGY.md, and the tests. Re-present every decision
   still open. Print the inventory block for the user to paste into
   .claude/protocol/pending: the plan path on the first line, then one path per
   line. Then halt.

6. When the user has approved §6 and refreshed the token, implement it. Apply
   the boyscout obligations to every file touched. Run the whole suite:
   sbt app/test, sbt 'commonJVM/test; server/test', the register_it_ cleanup,
   sbt serverIt/test. Report pass or fail only. Then propose the bump command
   and the commits.

Rules that bind every step: the global ~/.claude/CLAUDE.md, the project
CLAUDE.md gates G1–G8, the Teaching output style, and the project glossary's
vocabulary. No subagents. No new name is introduced for a concept the codebase
already names.
```

## 6. Fixes

Not written yet. Step 5 of the prompt writes it after the user rules on the
findings. The user creates `PLAN-SEMANTIC-DUPLICATES-INVENTORY.md` from the
block that step prints.

## 7. ADR alignment

The review reads code and writes one review file under `docs/dev/reviews/`, so
no record bears on it. Each fix in §6 carries its own ADR review. ADR-037
(closed sets in their owner's companion) and ADR-018 (nominal wrappers) are the
records most likely to decide which definition survives.

## 8. Terminology impact

None from the review. Each fix in §6 states its own.

## 9. Verification

For the review: `docs/dev/reviews/REVIEW-semantic-duplicates.md` exists, and
every candidate pair the script printed is either a finding or a dropped pair
with its reason.

For the fixes, each green:

```bash
sbt app/test
sbt 'commonJVM/test; server/test'
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm
sbt serverIt/test
```
