# A control entered as a fact or as a mitigation, and the other modelling concerns of ingestion

**Status: exploratory scratch. Not a plan, not a decision record, not a
decision.** It records the modelling concerns that came up when the goal was
set against the existing notes. The goal is to ingest manifests, Terraform and
compose files and software bills of materials, and to model which mitigations
are in effect on which assets. It builds on
`docs/scratch/LIFE-TABLE-LEAVES-AND-REACHABILITY.md`, called the life table
note below, and uses its example figures. Every figure is invented.

`docs/user/TERMINOLOGY.md`, the project's glossary, has no entry for an asset,
a control, a vulnerability, reachability or a rate. The life table note defines
them.

Part 1 is the concern the note is named after, with a rule that answers it.
Parts 2 to 7 are the further concerns. Part 8 lists what stays open.

| Part | Concern | State |
|---|---|---|
| 1 | One control counted twice, as a fact and as a mitigation | answered by a rule |
| 2 | Where a control's figure comes from | answered: a test or a judgement |
| 3 | Where the dates come from | answered: repeated ingestion |
| 4 | An asset reached only through another asset | open |
| 5 | One vulnerability on several assets | open |
| 6 | A tree grouped by vulnerability | two shapes, neither complete |
| 7 | Two methods for a likelihood | agree on one figure, differ on the tree |

---

# Part 1 — A control entered as a fact or as a mitigation

## The concern

A control can enter the model in two ways. The life table note, stage 8:

> A control that leaves no way in, such as the isolation of `batch`, is a
> fact, and it removes days. A control that reduces the rate, such as the
> firewall, is a mitigation. One control entered both ways is counted twice.

Take CVE-B on `db`, open 60 days, likelihood 0.19. The firewall worked on 30
of those days and blocks CVE-B.

```
entered as a fact:        30 days count           likelihood 0.10
entered as a mitigation:  scale 0.5 on 0.19       likelihood 0.095
entered both ways:        scale 0.5 on 0.10       likelihood 0.05
```

The third line credits the firewall twice.

A second effect follows from the first way. A control entered as a fact is
inside inherent risk (glossary 4.4). No mitigation exists for it, so no
selection (glossary 4.5) can value the tree with and without it. Its worth is
not reported, and a query for the mitigations in effect on an asset does not
show it.

---

## The rule

An expert states the control's efficiency once, for example "the firewall is
90% efficient". The reasoning over the ingested facts decides where the
firewall stands and on which days it works. The 90% is applied there, as a
mitigation, and nowhere else.

The rule splits the two questions between two owners:

| Question | Answered by | Form of the answer |
|---|---|---|
| Where and when does the control apply? | The reasoning over facts | A set of leaves |
| How much does it reduce? | The expert's figure, or a test | A factor on the likelihood |

This is the layout of the life table note's stage 8. Its three cases are "not
reachable", "reachable, firewall working" and "reachable, no working
firewall". The firewall mitigation targets the leaves of the second case.

---

## Whether the rule is consistent

It is, under one condition: **a control that carries an efficiency never
removes days.** The reasoning may use the firewall to sort a day into a case.
It may not use the firewall to derive "not reachable".

`docs/scratch/Argumentation-Engine.md`, section 4.1, breaks that condition as
written:

```prolog
blocked(X, Y)  :- firewall(M, _), covers(M, X, Y), effective(M).
can_talk(X, Y) :- ..., not blocked(X, Y).
```

There an effective firewall removes the connection outright. Rules of that
form and a 90% mitigation for the same firewall are the double count.

Three details follow from the rule.

- **The leaves need a place the mitigation can target.** A targeting predicate
  "may use only the tree's structure" (glossary 3.8). So the step that builds
  the tree puts the leaves of the "firewall working" case under their own
  portfolio, as stage 8 does.
- **A control that worked for part of a row cuts the row.** A mitigation has no
  dates. The row becomes two leaves at the day the control's state changed
  (stage 6).
- **A test result changes the figure for one vulnerability.** A test shows the
  firewall blocks a CVE or does not (stage 14). A mitigation holds one effect,
  so a firewall with tested and untested vulnerabilities becomes several
  mitigations.

---

## What the rule resolves

**The double count: resolved.** Each control has one way in.

**The missing worth: resolved for every control that carries an efficiency.**
The firewall is a mitigation, so a selection values it and `mitigate(x, m)`
shows the leaves it covers.

**Not resolved: a control that allows or forbids outright.** A NetworkPolicy
that denies all incoming traffic still enters as a fact. `batch` still has no
leaf, and the policy's worth is still not reported.

The rule can be extended to those controls. The policy then gets an efficiency
too, for example 99%. Row 8, CVE-C on `batch`, keeps its leaf:

```
inherent risk    0.29
residual risk    0.0029
```

The policy is now visible and can be valued. Two things change with that:

- Every row that a control makes unreachable keeps a leaf. The tree grows.
- A line is needed between a control and plain topology. A missing Service
  object is not a control anybody chose, and it has no efficiency.

Which controls get an efficiency is a modelling choice. This note does not
make it.

---

# Part 2 — Where a control's figure comes from

A manifest says a control exists and what it is configured to do. A mitigation
in the register needs a figure. The manifest supplies it for one kind of
control only.

**A control that allows or forbids outright.** A NetworkPolicy is one. Its
effect is yes or no, and the configuration states it. The one thing to check
is that the policy is enforced. The life table note's Kubernetes section says
of the network plugin: "Some do not, and then the objects have no effect."

**A control that reduces.** A web application firewall, a mail filter and
two-factor authentication are examples. The manifest shows the control is
deployed. It does not show what share of attacks it stops. That share has two
sources:

- **A test.** The exploit for one vulnerability is sent at the control. It is
  blocked or it is not (life table note, stage 14).
- **A judgement.** "We are 90% sure it blocks this." Later tests update the
  judgement (stage 15).

So assurance is not needed to build the model. Without it, every reducing
control carries a figure somebody chose, and the tree does not show which
figures were tested. The life table note, stage 18, names the same gap: a leaf
resting on a judgement and a leaf resting on 100 tests "look the same in the
tree".

---

# Part 3 — Where the dates come from

The tree has no dates and a mitigation has none. A manifest read once gives the
present state only. The life table method needs a found date and a fixed date
per row, and the days on which each control worked.

Reading the sources repeatedly, for example weekly, supplies the dates:

- A vulnerability's found date is the first reading that shows it.
- Its fixed date is the first reading that no longer shows it.
- A control's state changes at the first reading that shows the change.

Two limits follow:

- Every date is accurate to one interval between readings.
- A change that starts and ends between two readings is never seen. The
  temporary port on `admin` in the life table note was open for 15 days. A port
  open for three days could leave no trace in weekly readings.

The history starts at the first reading. Earlier periods need the sources the
life table note lists: git history, the audit log, the registry's push dates.

---

# Part 4 — An asset reached only through another asset

## The two kinds of reachability

Reachability from a fixed starting point, such as the internet, is known before
the trials run. It decides which days count, or it is a factor on the
likelihood. The simulation handles this. It is how reachability turns a bare
EPSS figure into a leaf's likelihood.

Reachability through another asset is different. Take `db`, reachable only from
`web`. Whether `db` can be attacked depends on whether `web` fell in the same
trial.

## The rule of Part 1 applied here

The rule would read: the reasoning says `db` sits behind `web`, so the leaves
of `db` get a factor. The factor is the chance that `web` falls. With `web` at
0.31 and `db` at 0.32, `db` comes out at about 0.10.

**The rule is not consistent here.** The firewall's 90% is a property of the
firewall and stays the same whatever else is in the tree. The 0.31 is computed
from the leaves of `web`, and it changes when they change.

Take a control on `web` that brings `web` from 0.31 to 0.05:

```
web                          0.31  ->  0.05
db, factor fixed at 0.31     0.10  ->  0.10      the tree
db, factor follows web       0.10  ->  0.016     the attack chain
```

A mitigation holds one fixed factor. So the tree reports the control's effect
on `web` and misses its effect on `db`. Inherent and residual risk for `db`
come out the same, and the control's worth is understated.

**The rule does not resolve the joint figures either.** The factor corrects the
figure of `db` alone, for one fixed state of `web`. The two events are still
simulated separately:

| | Tree with the factor | Attack chain |
|---|---|---|
| `db` exploited | 0.10 | 0.10 |
| Both exploited in one trial | 0.31 × 0.10 = 0.03 | 0.10 |
| At least one exploited | 0.38 | 0.31 |

The error is small where likelihoods are small. It sits in the trials with
several losses, which are the tail of the loss exceedance curve (glossary
1.11).

This is the limit of the life table note's stage 11.
`docs/scratch/DEPENDENCE.md` records its cause: "leaves simulate
independently".

---

# Part 5 — One vulnerability on several assets

The life table note makes one leaf per vulnerability per asset. Of CVE-A on two
machines, stage 7 says: "It is two rows and two leaves, because either machine
can be attacked through it." Each leaf gets the full likelihood derived from
the EPSS score.

The EPSS score is one figure per CVE for the whole world. Stage 1 of the life
table note says so: "The score describes exploitation seen anywhere in the
world. This note takes it as the chance for one machine that an attacker can
get to." That reading does not hold once the CVE is on many assets.

One CVE with score 0.10, open for 30 days on 10 assets:

| | Ten independent leaves | One shared event, every asset hit when it happens |
|---|---|---|
| At least one asset exploited | 1 − 0.9¹⁰ = 0.65 | 0.10 |
| All ten exploited | 0.1¹⁰ | 0.10 |

The independent tree gives the estate a chance of 0.65 of being exploited
through this CVE. The score gives the CVE a chance of 0.10 of being exploited
anywhere. The same tree puts the trial in which all ten assets fall at almost
zero, and that trial carries the largest loss. So the mean is overstated and
the tail is understated.

The life table note meets the same problem in three other places:

- Stage 18: leaves that share one uncertain figure are not independent.
- The Kubernetes section: three pods of one image "are not independent", and
  the workload is taken as the asset.
- Stage 11: the attack chain of Part 4.

None of them covers one CVE on several workloads. A software bill of materials
with shared base images produces that case for most of its vulnerabilities.

The simulation has no leaf whose event depends on another leaf's event. Each
leaf owns its random streams, and the glossary says of the per-trial sum:
"Combining this way models risks that are independent of one another" (1.8).

---

# Part 6 — A tree grouped by vulnerability

The CVE is the natural unit for the EPSS score, because the score is per CVE.
The tree can hold that unit in two shapes. Take CVE-1234 with score 0.10 on 10
containers: 4 unreachable, 3 behind the 90% firewall, 3 exposed.

## A portfolio per CVE, a leaf per container

```
CVE-1234                 (portfolio)
|-- 3 leaves at 0.10     exposed
\-- 3 leaves at 0.01     the firewall as a mitigation
```

Reachability removes 4 leaves. The firewall stays a mitigation with a reported
worth. Several containers can lose in one trial.

The leaves are still independent. The chance of at least one is
`1 − 0.9³ × 0.99³ = 0.29`, against a score of 0.10.

## One leaf per CVE

```
CVE-1234                 (leaf)   likelihood: 0.10 × the chance one of ours is hit
                                  loss: the total over the containers hit
```

One event stands for "this CVE is exploited", so the figure cannot exceed the
score.

The reductions per container are folded into one likelihood outside the
register. The firewall is then no longer a mitigation the register can value.
The loss has to be stated as a total over a set of containers that varies.
`docs/scratch/LIKELIHOOD-FROM-RATE.md`, section 3, describes the same move for
a frequent event: the leaf's loss stands for the period's total.

## What each shape keeps

| | Portfolio per CVE | One leaf per CVE |
|---|---|---|
| Reductions per container, as mitigations | kept | lost |
| The shared event per CVE | lost | kept |
| Several losses in one trial | kept, as independent | inside the one loss figure |

Having both needs one event per CVE and one event per container beneath it.
The simulation has no such leaf.

## Two consequences of grouping by CVE

- **A node has one parent.** A tree grouped by CVE has no portfolio per asset.
  An asset's figures would then come from a query predicate such as `on_asset`,
  which `docs/scratch/KNOWLEDGE-BASE-FOR-ASSURANCE-QUERIES.md` sketches, and a
  query returns a count, never a loss figure.
- **A single CVE has no arrival rate.** It is disclosed once. A group has one:
  a package, a base image or a severity class.

---

# Part 7 — Two methods for a likelihood

## Method A: one leaf per vulnerability on an asset

It is the life table note's method. It asks: this vulnerability was open on
this asset for these days, so how likely was an exploitation?

- Inputs: the CVE's EPSS score, and the days it was open and reachable.
- Example: CVE-B on `db`, score 0.10 per 30 days, open 60 days. Likelihood
  `1 − 0.9 × 0.9 = 0.19`.
- Result: one leaf per row.

## Method B: one leaf per image or fleet

It is the method of `docs/scratch/ASSURANCE-ANCHORED-PARAMETERS.md`. It asks:
vulnerabilities keep arriving and being fixed, so how likely is an exploitation
in a year?

- Inputs: how many arrive per year (90), how long one stays open (24 days), and
  the chance per year that an open one is exploited (0.05).
- Example: `90 × 24/365 = 5.9` are open at any moment. `5.9 × 0.05 = 0.30`
  exploitations are expected per year. Likelihood `1 − e^(−0.30) = 0.26`.
- Result: one leaf for the whole image.

## Where they agree

Both count expected exploitations as a rate times the time open. They give the
same chance of at least one exploitation under one condition: Method B's single
chance equals the average of Method A's rates per CVE, weighted by days open.

For the three rows of `db` in the life table note, the rates times the days sum
to 0.71 expected exploitations. Both methods then give `1 − e^(−0.71) = 0.51`,
which is the figure of the life table note's stage 5. This derivation is this
note's own.

## Where they differ

- **The tree.** Method A's three leaves can happen in the same trial, and each
  draws a loss. Method B's one leaf happens at most once and draws one loss.
  The loss figures differ where the likelihood agrees.
- **The detail.** Method A keeps each CVE, each asset and each period.
  Reachability, and a control that covers some rows, need that detail. Method B
  has folded it into the arrival count.
- **How a change is stated.** Faster patching is an edit to one input in
  Method B. In Method A it shortens every row.
- **The example figures.** The life table rows imply about 1.9 exploitations
  per open vulnerability per year. The assurance note uses 0.05. Both are
  invented, and no note relates them.

Repeated ingestion (Part 3) produces Method A's rows directly. Method B's
inputs are summaries of the same rows. No note says which of the two the
ingestion writes into the tree. Method B applied per group of vulnerabilities
sits between the two.

---

# Part 8 — What stays open

1. **Dependence between leaves.** Parts 4, 5 and 6 all end at the same limit.
   `docs/scratch/DEPENDENCE.md` and item 45 of `docs/dev/TODO.md` park it until
   the asset graph exists.
2. **Which controls get an efficiency** (Part 1).
3. **Which method writes the leaves, and at which grouping** (Parts 6 and 7).
4. **Targeting by asset.** A targeting predicate "may use only the tree's
   structure" (glossary 3.8). A mitigation in effect on an asset can be written
   today only where the tree has a portfolio for that asset.
5. **Whose loss a leaf draws.** The business states a loss distribution for an
   asset. Every CVE leaf under the asset draws from it separately, so two
   leaves happening in one trial add the asset's loss twice. The life table
   note's stage 5 intends this. Whether a second exploitation of one asset
   costs the full loss again is not examined in any note.
6. **The step that builds the tree.** The life table note lists it among what
   the register does not have. This is known and outstanding.
