# Towards a GRC tool — quick wins and open questions

**Status: exploratory scratch. Not a plan, not a decision record, not a
decision.** It lists small additions that would move the register towards a
governance, risk and compliance tool, answers four questions about them, and
records the questions that need investigation before any of it is planned. It
builds on `docs/scratch/LIFE-TABLE-LEAVES-AND-REACHABILITY.md`, called the life
table note below, whose stage numbers it cites.

`docs/user/TERMINOLOGY.md`, the project's glossary, has no entry for any of the
GRC terms used here. Each is defined where it first appears. Statements about
what GRC tools usually offer are from general knowledge and were not checked
against any product.

---

## What a GRC tool adds to a risk register

**GRC** stands for governance, risk and compliance. A GRC tool records who is
accountable for what, which requirements apply, which controls meet them, what
evidence shows the controls work, what audits found, and what is being done
about it.

The register already does the analysis part well. It values a tree of risks,
and it values the same tree with and without a chosen set of mitigations, which
the glossary calls a selection (4.5). What it lacks is the record around that
analysis. Today a leaf holds an identifier, a name, its parent, a likelihood
(the field is named `probability` in the code), a loss distribution and a seed. A mitigation holds an identifier, a name, a
target, a spec and a precedence. Neither holds a description, an owner, a date,
a requirement or a test.

From `modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala`:

```scala
final case class Mitigation private (
  id: MitigationId,
  name: SafeName.SafeName,
  target: MitigationTarget,
  spec: MitigationSpec,
  precedence: MitigationPrecedence
)
```

**One constraint applies to every addition below.** None of these fields
changes a simulation result. ADR-032 keeps the cache key on a projection of the
leaf that holds the simulation inputs only, `LeafSimContent`. The glossary
calls the hash of that projection the leaf's fingerprint. ADR-032 lists
"Growing `LeafSimContent` beyond simulation inputs" as a mistake. So every
field here is stored with the node and kept out of that projection. Editing an
owner then re-simulates nothing.

**What "quick" means here.** Each addition is small in code. Each one also
changes a domain type, a wire shape and the persisted format, and the project
treats all three as guarded changes. So each needs a plan. "Quick" describes
the size of the change, not the path to it.

---

## The quick wins

Ordered by how much each gives for its size.

| # | Addition | On | What it gives |
|---|---|---|---|
| 1 | Description | leaf, portfolio, mitigation | What the risk or the control is, in words. Every GRC record starts here |
| 2 | Owner | leaf, portfolio, mitigation | Who is accountable. A **risk owner** accepts the residual risk. A **control owner** runs the control and supplies its evidence |
| 3 | Status and due date | mitigation | A **risk treatment plan**: what is planned, in progress, implemented or retired, and by when |
| 4 | Requirement references | mitigation | Which framework requirements the control meets, as a structured pair (see below) |
| 5 | Review date | leaf, portfolio | When the risk was last reviewed and when the next review is due. An ISMS requires periodic review |
| 6 | Test records | mitigation | The evidence the control works, and the input to the update of stage 15 (see below) |
| 7 | Appetite point | the tree's root | One loss and one chance, such as "a loss of 10 million at most 1 year in 20". The root's loss exceedance curve is out of appetite where it passes above that point. A screening query can already test one such point, with `gt_prob` and `lec`. The addition is storing the point with the tree |
| 8 | Control report | read only | Per mitigation: owner, status, requirements, tests, and its worth: the inherent figure against the figure with that one mitigation selected (glossary 4.5). No new data, only a view of items 1 to 6 beside the existing valuation |

An **ISMS**, an information security management system, is the management
cycle ISO 27001 defines: scope, policy, risk assessment, risk treatment,
operation, checking, review, correction.

Items 1, 2, 3 and 5 are recorded data only. Items 4 and 6 have structure worth
getting right first. Item 7 uses the loss exceedance curve the register
already produces. Item 8 joins them.

---

## Grouping the quick wins for priority

### The dimensions

Five properties of each addition bear on its priority:

- **Kind of change.** A text field, a value from a closed set, a date, a list
  of records, or a read-only view. The kind decides how much design the
  addition needs before a plan can state its exact types.
- **What it waits on.** Some additions depend on an open question below. The
  number in the column is that question's number. Question 1, whether the data
  belongs to a control or to a mitigation, bears on every addition made to a
  mitigation.
- **Whether it feeds the analysis later.** Some fields are records only. Two
  become inputs to the figures once the matching design change is made: the
  due date leads to dated mitigations, and the test records lead to the update
  of stage 15.
- **Who reads it.** Management, the compliance function, the audit and
  assurance functions, or everyone.
- **Which type it changes.** A leaf, a portfolio, a mitigation or the tree's
  root. The cost of an addition is mostly a cost per changed type: one wire
  shape, one persisted format and one plan per type, whatever the number of
  fields added to it.

| # | Addition | Kind of change | Waits on | Feeds the analysis later | Main reader | Type changed |
|---|---|---|---|---|---|---|
| 1 | Description | text | nothing | no | everyone | leaf, portfolio, mitigation |
| 2 | Owner | text, later a user identity | 2 for the identity form; 1 on a mitigation | no | management, audit | leaf, portfolio, mitigation |
| 3 | Status and due date | closed set and a date | 1 | yes, through dated mitigations (8) | management | mitigation |
| 4 | Requirement references | closed set and checked text | 1; 5 for a gap analysis | no | compliance, audit | mitigation |
| 5 | Review date | a date | nothing | no | the ISMS cycle, audit | leaf, portfolio |
| 6 | Test records | a list of records | 1, 6, 7 | yes, through the update of stage 15 | assurance, audit | mitigation |
| 7 | Appetite point | two numbers and a comparison | nothing | it reads the curve | management, the board | the tree's root |
| 8 | Control report | read only | items 1 to 6 | it reads the valuation | everyone | none |

### What is worth implementing together

The last column decides most of the grouping. A plan that changes a type pays
for the wire shape, the persisted format and the tests once. A second field on
the same type in the same plan costs little more. A second plan for the same
type pays it all again. So additions to one type belong in one plan, unless
one of them waits on something the others do not.

That gives four groups.

**Group A — record fields on leaves and portfolios: items 1, 2 and 5.** A
description, an owner as text and a review date. They wait on nothing, because
question 1 concerns mitigations only. They change two types and nothing else.

**Group B — record fields on mitigations: items 1, 2 and 3.** A description,
an owner as text, a status and a due date. They wait on question 1. If the
control of `PLAN-CONTROL-GROUPING.md` is built, these fields move to it, so
adding them to the mitigation first means moving them later. If they are added
to the mitigation regardless, Group A and Group B can share one plan.

**Group C — assurance: items 4 and 6, with the parts of item 8 that read
them.** Requirement references and test records, plus the report columns that
show them: requirements per control, the tests, the weight `a + b`, and what a
finding is worth. The report belongs in the same group because test records
nobody can see change nothing for a reader. Requirement references join the
group because a test record and a requirement are checked together in an
audit: the requirement says what the control must do, the test says whether it
did. This group waits on questions 1, 6 and 7.

**Group D — the appetite point: item 7.** Two numbers on the root and one
comparison with the root's loss exceedance curve. It waits on nothing and
touches nothing the other groups touch. It can go into any plan or into its
own.

Item 8 is built in parts. Each group adds the report columns for the data it
adds. A report designed ahead of its data would carry columns with nothing in
them.

### A suggested order

This order is this note's own suggestion.

1. **Group A,** because it waits on nothing and gives every risk an owner and
   a review date. An ISMS audit checks those first.
2. **Group D,** because it is small, independent, and gives management one
   statement it can act on: whether the tree is inside its appetite.
3. **Question 1 answered, then Group B.** The answer decides where every
   mitigation field lives, so the fields are written in their final place
   once.
4. **Group C,** last, because it waits on the most and needs the most design.
   It is also the group that most sets the register apart from a usual GRC
   tool, because it is where the evidence joins the figures. Building it last
   means building it on a settled place for control data.

---

## Owners

Owners help, and they are the cheapest addition with the largest effect on how
a GRC function sees the tool. Accountability is the first thing an ISMS audit
checks: every risk and every control has a named owner.

The two kinds of owner answer different questions. The risk owner decides
whether the residual risk is accepted. The control owner keeps the control
working and answers for its tests. One person can be both, and often is not.

Two forms are possible.

- **A name or a role as text.** It works today, in every workspace, with no
  identity layer. It cannot notify anyone, and it cannot be checked against
  who is logged in.
- **A user identity.** The register has a `UserId` type and reads an
  `x-user-id` header, and its authorization plan relies on an identity
  provider. A workspace reached by its capability URL alone has no user, so an
  owner as a user identity works only where that layer is deployed.

Text is the quick win. The identity form is an open question below.

---

## An implementation timeline

A timeline helps in two separate ways, and they should not be merged.

**As a plan.** A status and a due date per mitigation make the register a risk
treatment plan: the list of agreed controls, who delivers them and by when.
This is recorded data and changes no figure. It is quick win 3.

**As an effect on the figures.** A control that was implemented in June
reduced risk only from June. The life table note handles this by cutting a row
into two leaves on the day a control's state changed (stage 6), and it lists
"Dates on a mitigation" among what the register does not have. Dated
mitigations would let the register apply a control only to the days after its
start. That changes how a mitigation is applied, so it is a design change
inside the simulation, not a quick win.

The two dates are different facts. A due date is a plan. An effective date is
what happened, and it can be later, or interrupted, as the firewall's
misconfiguration in stage 6 was. A test record (below) is what shows the
effective date.

The plan form joins `docs/dev/plans/PLAN-MITIGATION-ROI.md`, which needs a cost
per mitigation. A treatment plan usually records the cost beside the due date.
`docs/scratch/MITIGATION-ROI.md` gives the cost two shapes: a yearly fee, or an
upfront fee plus yearly maintenance. The upfront fee falls due when the control
is implemented, so the due date and the cost describe the same event.

---

## Requirement references: is a text field enough?

A text field such as "ISO 27001 A.8.8; DORA Art. 9(2)" is enough for reading a
mitigation. It is not enough for compliance reporting.

Compliance reporting asks the inverse question. Starting from a requirement,
it asks which controls meet it, what evidence they have, and which
requirements no control meets. The last question is called a **gap analysis**.
ISO 27001's **Statement of Applicability** is the document that records, per
requirement, whether it applies and which controls meet it.

A text field answers the inverse question only by searching strings. That
fails in two ways.

- **Spelling.** "ISO 27001 A.8.8", "ISO27001:2022 8.8" and "27001 8.8" are the
  same requirement. A search for one misses the others.
- **Absence.** A gap is a requirement with no control. Free text lists only the
  requirements somebody wrote down, so it cannot show one nobody did.

A middle form costs about as much as the text field and removes the first
failure: each reference is a pair, a framework from a closed set and a section
identifier.

```
framework  ISO 27001:2022     section  A.8.8
framework  DORA               section  Art. 9(2)
```

The closed set of frameworks is a fixed list in the code. The section
identifier is checked text. Grouping by framework and by section then works,
and the Statement of Applicability can be produced for the requirements that
appear.

The second failure needs a **requirements catalogue**: the full list of a
framework's requirements, loaded into the tool. Only then can it show a
requirement no control meets. That is an open question below.

My recommendation: the structured pair as the quick win, and the catalogue
later. The free text field is a shortcut whose only consumer today is a human
reader, and the shape of the present consumer is no argument for dropping the
structure.

---

## Test records, and how they join the analysis

### What a test record holds

A test record is one check of one control:

| Field | Example |
|---|---|
| Control | the firewall |
| Scope | CVE-B, or "all changes in March" |
| Date | 12 March |
| Kind | design test, operating test, penetration test, attack simulation, or field counts |
| Result | blocked or passed. For a sample: how many passed and how many failed |
| Tester | a name or a role |
| Evidence | a link to the report |

GRC practice distinguishes two kinds of test. A **test of design** asks whether
the control, as configured, would meet its purpose. A **test of operating
effectiveness** asks whether it did, over a period, usually by checking a
sample. A failed test becomes a **finding**: a recorded gap with an owner and a
remediation date.

### Where each record goes in the analysis

The life table note already says what each kind of result does to the figures.

- **A test of one vulnerability that blocked.** This is a fact. The row has no
  day that counts (stage 14), and the control's effect now sits inside
  inherent risk.
- **A test of one vulnerability that passed.** The control changes nothing for
  that row, and the row counts in full (stage 14).
- **A sample, or field counts.** These are observations. They update the belief
  about the control's pass share by the beta update of stage 15: add the
  passes to `a`, the blocks or the conforming items to `b`. Stage 15's example:
  a judgement of Beta(2, 18) and a test where 4 of 100 pass give Beta(6, 114),
  mean 0.05.
- **No test.** The judgement alone stands, stated as a range (stage 15).

So the test records are the data from which a mitigation's figure is derived,
and the figure is what the register applies. A record belongs to the audit
side of a GRC tool and to the input side of the analysis at once.

### What the audit section can then show

Joined with the analysis, an audit view per control could show:

- **How well founded the figure is.** The sum `a + b` is the weight of the
  belief, counted in observations (stage 15). A control resting on a judgement
  worth 20 observations and one resting on 120 look the same in a plain
  compliance report.
- **What a finding is worth.** Value the tree with the control as believed and
  with the control as tested. The difference is the finding's effect in loss.
  Stage 14's example: testing showed the firewall does not block CVE-E, and the
  firewall's worth for the year fell from 0.12 to 0.03. Findings can then be
  ranked by that difference.
- **Where testing would pay.** The band of stage 18 shows how far the result
  could move with the figure. A wide band on a control that carries much of the
  risk is where the next test belongs. A narrow one says another test changes
  little, which was the result for the firewall in stage 18.
- **Which evidence is old.** A test from two years ago says less than one from
  last month. How much less is an open question below.

### What the register has for this today

Nothing. A mitigation holds one spec, written by a person. With test records,
the spec's figure would be derived from the records and a judgement, which is
the gap "The update itself" in the life table note. Recording the tests is a
quick win. Deriving the figure from them is a design change.

---

## Open questions

These need investigation before any of the above is planned. None is ruled.

1. **Control or mitigation.** `docs/dev/plans/PLAN-CONTROL-GROUPING.md` states:
   "A **control** is what a person authors: one named risk-reduction measure",
   and observes that one control with two effects is today two mitigations.
   An owner, a status, requirements and tests belong to what a person
   authors, and so does the cost of `docs/scratch/MITIGATION-ROI.md`. Do they
   attach to the control of that plan, or to the mitigation? That plan is
   marked "NOT PLANNED, NOT RULED, NOT RESEARCHED", so this question depends
   on it. The plan's own reason for a control is how effects compose. It names
   none of these fields, so they are a second reason for the same grouping.
   One of the plan's open decisions bears on the test records. Stage 14 of the
   life table note gives one firewall three effects on three groups of leaves:
   blocked, not blocked and not tested. The plan's `CG-D-4` asks whether a
   member may narrow its control's scope, and calls that "a capability nobody
   has asked for yet". Per-vulnerability test results ask for it.
2. **Owners as user identities.** What does an owner become in a workspace
   reached by its capability URL alone, which has no user? Does the identity
   provider's user identifier cross the wire, and under which rule of ADR-036?
   Who may change an owner?
3. **Approvals.** Which acts need an approval: accepting a residual risk,
   marking a control implemented, accepting a test result, closing a finding?
   ADR-007 makes a scenario a branch, so one candidate is to make an approval
   the merge of a branch into the main line. Does a merge carry who approved
   it, and does it support a second person approving (the four-eyes
   principle)? ADR-024 makes the application a read-only enforcement point for
   authorization data. Is an approval record domain data, which the
   application may write, or authorization data, which it may not?
4. **Shared control libraries.** A Group wants one library of controls used by
   many entities. The workspace is the register's tenant boundary (ADR-021,
   ADR-036). Does a library live outside every workspace, and how does a
   workspace reference it without learning another workspace's identifier? Is
   a control copied into a tree, or referenced and versioned? Irmin, the
   store, addresses content by hash. Can one stored control be shared by hash
   across trees?
5. **The requirements catalogue.** Which frameworks, and in what format?
   OSCAL, the Open Security Controls Assessment Language from NIST, is a
   machine-readable format for control catalogues. ISO 27001's text is under
   copyright, so a catalogue may hold only the identifiers and short titles.
   NIST's frameworks and EU regulations such as DORA are public.
6. **Evidence storage.** Does a test record link to evidence held elsewhere,
   or does the register store files? Stored files raise retention,
   confidentiality and size questions that links do not.
7. **The age of evidence.** How does an old test lose weight? One candidate
   is to scale its counts down with age before the update. This is my
   proposal, from no source, and it needs a source.
8. **Dated mitigations in the simulation.** How does a mitigation with an
   effective date apply to a leaf that covers a period? Cutting leaves on the
   date is what stage 6 does by hand. Is that the mechanism, or a share of
   days on the leaf?
9. **A Group roll-up.** Entity trees under one Group tree. A control shared by
   several entities is one figure under several leaves, which stage 18 showed
   makes them dependent, and the register simulates leaves independently. How
   large is the understatement for a realistic shared control?
10. **Qualitative bands.** Management reports in banks often use a heat map
    with likelihood and impact on fixed scales. What rule maps a loss
    exceedance curve onto such a scale, so the register feeds existing reports?
11. **An audit trail with authors.** The store records every change as a
    commit. The register's store client passes a message with each commit and
    no author: `IrminClient.set(path, value, message, branch)`. How does a
    commit come to record who made it, and is that enough for an auditor's
    change history?
12. **The name.** DORA requires financial firms to keep a "register of
    information" on their ICT third-party arrangements. The project's name
    overlaps with it. Does that matter where the tool is presented?
