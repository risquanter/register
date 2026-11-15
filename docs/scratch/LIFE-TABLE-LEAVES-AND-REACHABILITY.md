# From one vulnerability to a risk tree, with reachability — a worked build-up

**Status: exploratory scratch. Not a plan, not a decision record, not a
decision.** It is a teaching note. It joins two other scratch notes:
`docs/scratch/ASSURANCE-ANCHORED-PARAMETERS.md`, which turns measured
vulnerability data into a leaf's likelihood, and
`docs/scratch/Argumentation-Engine.md`, which derives yes or no facts about
assets from rules. Every figure in the examples is invented for this note.

`docs/user/TERMINOLOGY.md`, the project's glossary, has no entry for a life
table, for reachability or for an asset. This note defines each where it first
appears. Where a term is the glossary's, the glossary section is named.

---

## Where this note goes

It has two parts. Part 1 starts with one vulnerability on one machine and ends
with a tree of risks for four machines. It takes every number it uses as known.
Part 2 asks where each of those numbers comes from, how to say how sure we are
of it, and what the simulation does with that. Each stage adds one idea to the
stage before it.

Part 1, the tree:

| Stage | The one idea it adds |
|---|---|
| 1 | A published score becomes the chance of an event |
| 2 | The chance depends on how many days the vulnerability was open |
| 3 | The event gets a cost, and the simulation plays it out |
| 4 | A control reduces the chance |
| 5 | Several vulnerabilities on one machine are added up |
| 6 | A control that worked for only part of the time |
| 7 | Several machines form one tree |
| 8 | Reachability: whether an attacker's traffic could arrive at all |
| 9 | A second kind of reachability, inside the software |
| 10 | Two sources of information that disagree |
| 11 | One machine attacked through another, and why that does not fit |
| 12 | What the finished tree is used for |

Part 2, the numbers behind the tree:

| Stage | The one idea it adds |
|---|---|
| 13 | A likelihood rests on three inputs, and each has a source |
| 14 | The firewall again: what a figure such as 90% can mean |
| 15 | Three ways to state a number we are not sure of |
| 16 | Which single number the leaf receives |
| 17 | The same method for a rate, and a rate that changes from day to day |
| 18 | What the range is for, and one figure shared by several leaves |
| 19 | Risks that have no row in a life table |
| 20 | Joining the two views by splitting an event into causes |
| 21 | Checking the assumptions Part 1 made |

By stage 8 the tree gives three answers to one question: how likely was at
least one successful attack in the year?

| How the year is valued | Chance of at least one |
|---|---|
| Every vulnerability counted for every day it was open | 0.86 |
| Only the days an attacker's traffic could arrive | 0.70 |
| The same, with the firewall applied | 0.58 |

The steps between those three figures are what reachability and the firewall
are each worth.

---

## Stage 1 — one vulnerability, one machine, thirty days

A **vulnerability** is a flaw in software that an attacker can use. A publicly
disclosed one has an identifier that starts with CVE, which stands for Common
Vulnerabilities and Exposures. This note calls its examples CVE-A to CVE-F.

**Exploitation** is an attacker using the flaw successfully.

The **Exploit Prediction Scoring System**, EPSS, publishes one score per CVE
every day. The score is a probability: the chance that exploitation of that CVE
is observed within the next 30 days.

Take a database server called `db`. It has CVE-B, whose score is 0.10. Suppose
CVE-B stayed on `db` for exactly 30 days before it was fixed.

The risk register describes a **risk** as an uncertain event with two parts
(glossary 1.1). The first part is its **likelihood**: the chance the event
happens in the period being modelled. Here the event is "CVE-B on `db` is
exploited", and the likelihood is the score:

```
likelihood = 0.10
```

One simplification is made here and kept through Part 1. The score describes
exploitation seen anywhere in the world. This note takes it as the chance for
one machine that an attacker can get to. Stage 8 deals with "can get to".
Stage 17 corrects the figure with our own records.

---

## Stage 2 — the vulnerability was open for a different number of days

CVE-B was in fact open on `db` for 60 days. The likelihood is not 0.20.

The reason comes from looking at the chance that nothing happens. In one 30-day
period the chance of no exploitation is 0.90. For nothing to happen in 60 days,
nothing must happen in the first 30 and again in the second 30:

```
chance of nothing in 60 days = 0.90 × 0.90 = 0.81
likelihood                    = 1 − 0.81   = 0.19
```

Doubling the days does not double the chance, because an exploitation in the
second period only counts if the first period passed without one.

The same reasoning works for any number of days once the score is turned into a
**daily rate**: the expected number of exploitations per day. Call the rate `r`
and the score `s`. With the same rate every day, the chance of nothing in 30
days is `e^(−30r)`, and that must equal `1 − s`:

```
r = −ln(1 − s) / 30
likelihood for any number of days = 1 − e^(−r × days)
```

For CVE-B, `r = −ln(0.90) / 30 = 0.00351` per day. Over 60 days that gives
`1 − e^(−0.2107) = 0.19`, the same answer as above.

Two more vulnerabilities on `db`, used from stage 5 onward:

| Vulnerability | Score | Days open | Daily rate | Likelihood |
|---|---|---|---|---|
| CVE-A | 0.02 | 35 | 0.00067 | 0.023 |
| CVE-B | 0.10 | 60 | 0.00351 | 0.190 |
| CVE-E | 0.30 | 40 | 0.01189 | 0.378 |

The formula assumes the rate is the same on every day and that one exploitation
does not cause the next. It is less accurate where attacks cluster, for example
in the days after an attack tool is published. Stage 17 drops the first
assumption by reading a new score for every day.

---

## Stage 3 — the event gets a cost, and the simulation plays it out

The second part of a risk is its **impact**: how much is lost if the event
happens (glossary 1.1). The register does not take one figure for this. It takes
a **loss distribution**, which is a statement of the range (glossary 1.4). The
shortest form gives two bounds: the loss is below the lower bound one time in
twenty and above the upper bound one time in twenty.

For `db`, say 1 million to 20 million. Neither the EPSS score nor anything else
in the technical data supplies this. Somebody who knows what the database holds
has to state it.

A risk recorded in the register is a **leaf** (glossary 1.1). The leaf so far:

```
CVE-B on db     likelihood 0.19     loss 1 to 20 million
```

The register works with it by **simulation** (glossary 1.5). It plays the year
out ten thousand times. One play is a **trial**. In each trial it does two
things with the leaf:

1. It flips a coin weighted by the likelihood. With 0.19, the coin says "it
   happened" in about 19 trials in 100.
2. If the coin said yes, it draws one loss from the range. If it said no, the
   loss in that trial is 0.

After ten thousand trials there are ten thousand losses, about 8 100 of them
zero. Every figure the register reports is counted off that list. One example:
the share of trials with a loss of at least 5 million is the chance of losing
5 million or more.

---

## Stage 4 — a control reduces the chance

A firewall sits in front of `db`. The register calls a risk reduction measure a
**mitigation** (glossary 3.1). It is recorded beside the leaf, not folded into
the leaf's figures. That lets the register value the leaf both ways:

- **Inherent risk** is the leaf before any mitigation (glossary 4.4).
- **Residual risk** is the leaf after its mitigations (glossary 4.4).

A mitigation that changes a leaf's likelihood before the simulation runs is a
**parameter-stage** mitigation (glossary 3.2). It can state its effect in two
ways (glossary 3.6):

- A **relative statement**: "cut the likelihood to a tenth of what it was."
- An **absolute statement**: "with this control the likelihood is 0.02."

Suppose the firewall stops 90% of attack attempts for all 60 days. That is a
simplification. Part 1 keeps it, and stage 14 examines what such a figure can
mean for a device that decides by rule. One tenth of the daily rate remains:

```
likelihood = 1 − e^(−0.1 × 0.00351 × 60) = 0.021
```

So inherent risk is 0.19 and residual risk is 0.021 for this leaf.

One detail matters later. The firewall reduces the rate. A relative statement
in the register multiplies the likelihood, and that gives `0.19 × 0.1 = 0.019`.
The two are close and not equal. They agree when likelihoods are small.

---

## Stage 5 — several vulnerabilities on one machine

Over a year `db` had three vulnerabilities, each open for a while. A table of
them, with the day each was found and the day each was fixed, is a **life
table**. A row is open from the first day up to, and not including, the second.

| Row | Vulnerability | Found | Fixed | Days open | Likelihood |
|---|---|---|---|---|---|
| 4 | CVE-A | 10 Jan | 14 Feb | 35 | 0.023 |
| 5 | CVE-B | 2 Mar | 1 May | 60 | 0.190 |
| 6 | CVE-E | 10 Jun | 20 Jul | 40 | 0.378 |

The row numbers match the full table in stage 8.

Each row becomes its own leaf. The three leaves go under one **portfolio**: a
node whose value comes entirely from the nodes beneath it (glossary 1.2).

```
db                    (portfolio)
|-- CVE-A on db       (leaf)  0.023
|-- CVE-B on db       (leaf)  0.190
\-- CVE-E on db       (leaf)  0.378
```

In each trial the register flips each leaf's coin separately and adds the three
losses. The glossary calls this per-trial addition **combine** (1.7), and says
of it: "Combining this way models risks that are independent of one another."
Independent means one vulnerability being exploited says nothing about whether
another is.

The chance of at least one exploitation on `db` follows from the chance of
none. None happens when all three coins say no:

```
chance of none         = 0.977 × 0.810 × 0.622 = 0.49
chance of at least one = 1 − 0.49              = 0.51
```

**Why three leaves and not one leaf of 0.51.** One leaf can happen at most once
in a trial and draws one loss. With three leaves, two can happen in the same
trial, and both losses are added. Here that is 8% of trials. One leaf would
leave those second losses out.

---

## Stage 6 — the control worked for only part of the time

The firewall was misconfigured from 1 April until it was corrected on 1 July.
On those days it stopped nothing.

Row 5 was open from 2 March to 1 May. So for 30 of its days the firewall
worked, and for 30 it did not. A mitigation in the register applies to a leaf
or it does not, and it has no dates. So the row is cut into two leaves at the
day the firewall's state changed. Row 6 is cut the same way.

| Leaf | Days | Firewall on those days | Inherent likelihood | Residual likelihood |
|---|---|---|---|---|
| 4 CVE-A on `db` | 35 | working | 0.023 | 0.0024 |
| 5a CVE-B on `db`, 2–31 Mar | 30 | working | 0.100 | 0.0105 |
| 5b CVE-B on `db`, 1–30 Apr | 30 | misconfigured | 0.100 | 0.100 |
| 6a CVE-E on `db`, 10–30 Jun | 21 | misconfigured | 0.221 | 0.221 |
| 6b CVE-E on `db`, 1–19 Jul | 19 | working | 0.202 | 0.0223 |

Cutting a row changes no figure. With the same rate every day, what happens in
March says nothing about April, so the two parts are independent. Leaves 5a and
5b together give `1 − 0.90 × 0.90 = 0.19`, which is row 5 from stage 2.

The firewall then targets leaves 4, 5a and 6b only. For `db` as a whole:

| | Chance of at least one exploitation on `db` |
|---|---|
| Inherent risk | 0.51 |
| Residual risk, firewall working all year | 0.07 |
| Residual risk, with the three months of misconfiguration | 0.32 |

The misconfiguration covered a quarter of the year. It gave back more than half
of the firewall's benefit, because the highest-scoring vulnerability was open
during it.

---

## Stage 7 — several machines form one tree

A second machine, `web`, is a web server that faces the internet. No firewall
stands in front of it. It had three vulnerabilities in the year:

| Row | Vulnerability | Found | Fixed | Days open | Score | Likelihood |
|---|---|---|---|---|---|---|
| 1 | CVE-A | 10 Jan | 29 Jan | 19 | 0.02 | 0.013 |
| 2 | CVE-C | 5 May | 25 May | 20 | 0.40 | 0.289 |
| 3 | CVE-D | 1 Sep | 11 Oct | 40 | 0.01 | 0.013 |

CVE-A appears on both machines. It is two rows and two leaves, because either
machine can be attacked through it.

`web` gets its own portfolio, and both portfolios go under one root. The
glossary calls a root portfolio and everything beneath it a **tree** (1.2).

```
Estate               (portfolio)
|-- web              (portfolio)   3 leaves
\-- db               (portfolio)   5 leaves
```

The register adds losses up the tree trial by trial. Each portfolio gets its
own figures, and so does the root.

| Node | Inherent risk | Residual risk |
|---|---|---|
| `web` | 0.31 | 0.31 |
| `db` | 0.51 | 0.32 |
| Estate | 0.66 | 0.53 |

So far every vulnerability has been counted for every day it was open. Stage 8
questions that.

---

## Stage 8 — reachability: could an attacker's traffic arrive at all?

Two more machines join the estate, each with one row in the life table:

| Row | Vulnerability | Machine | Found | Fixed | Days open | Score |
|---|---|---|---|---|---|---|
| 7 | CVE-F | `admin` | 20 Jul | 28 Sep | 70 | 0.20 |
| 8 | CVE-C | `batch` | 5 May | 25 May | 20 | 0.40 |

Counted as before, row 7 has a likelihood of 0.41 and row 8 of 0.29. The estate
would stand at 0.86.

That count assumes an attacker could get to each machine on every one of those
days. A machine is **reachable** on a day when traffic from where the attacker
starts can arrive at it on that day. In this stage the attacker starts on the
internet.

Whether a machine is reachable is not in the life table. It comes from facts
about the network. From here on the note calls a machine an **asset**, which is
the word the logic note uses. Which object counts as one asset is a choice. The
Kubernetes section at the end shows what the choice decides.

- `web` faces the internet all year.
- `db` is in the same part of the network as `web`. Internet traffic can arrive
  at it by that way, and the firewall stands on that way.
- The firewall was misconfigured from 1 April to 1 July.
- `admin` has a management port. A temporary rule opened it to the internet
  from 1 August to 16 August. On other days nothing outside can get to it.
- `batch` has no way in from the internet on any day.

`Argumentation-Engine.md` describes how such facts are processed. Facts are
written down, and **rules** derive further facts from them. A rule has the form
"if these facts hold, this fact holds". The result of a rule is yes or no, never
a number. Two rules from that note are enough here. One derives whether traffic
can pass between two assets. The other derives whether a firewall is working:

```prolog
effective(M) :- firewall(M, _), not bypassed(M).
```

Read: a firewall `M` is effective if it exists and is not bypassed. A
misconfiguration is what bypasses it.

That note's facts have no dates. This example adds them, because the question
here is asked per day.

The derived facts put every open day of every row into one of three cases:

| Case | What was derived for that day | Share of the daily rate that counts |
|---|---|---|
| Not reachable | no way in from the internet | none |
| Reachable, firewall working | a way in exists and passes a working firewall | one tenth |
| Reachable, no working firewall | a way in exists, firewall absent or misconfigured | all of it |

Applied to all eight rows:

| Row | Days open | Not reachable | Reachable, firewall working | Reachable, no working firewall |
|---|---|---|---|---|
| 1 CVE-A on `web` | 19 | 0 | 0 | 19 |
| 2 CVE-C on `web` | 20 | 0 | 0 | 20 |
| 3 CVE-D on `web` | 40 | 0 | 0 | 40 |
| 4 CVE-A on `db` | 35 | 0 | 35 | 0 |
| 5 CVE-B on `db` | 60 | 0 | 30 | 30 |
| 6 CVE-E on `db` | 40 | 0 | 19 | 21 |
| 7 CVE-F on `admin` | 70 | 55 | 0 | 15 |
| 8 CVE-C on `batch` | 20 | 20 | 0 | 0 |

Reachability did three things in that table.

**[R1] It removed a row.** Row 8 has no reachable day. It produces no leaf. The
same vulnerability with the same score on `web`, row 2, does.

**[R2] It shortened a row.** Row 7 was open for 70 days and reachable for 15.
Its likelihood is 0.11, where 70 days would give 0.41.

**[R3] It said on which days the firewall was working.** That is the cut made
by hand in stage 6. Here it is derived from the facts.

The tree:

```
Estate                                   (portfolio)
|-- web                                  (portfolio)
|   |-- CVE-A on web                     (leaf)  0.013
|   |-- CVE-C on web                     (leaf)  0.289
|   \-- CVE-D on web                     (leaf)  0.013
|-- db                                   (portfolio)
|   |-- db, firewall working             (portfolio)   <- the firewall targets every leaf under this
|   |   |-- CVE-A on db                  (leaf)  0.023
|   |   |-- CVE-B on db, 2-31 Mar        (leaf)  0.100
|   |   \-- CVE-E on db, 1-19 Jul        (leaf)  0.202
|   \-- db, no working firewall          (portfolio)
|       |-- CVE-B on db, 1-30 Apr        (leaf)  0.100
|       \-- CVE-E on db, 10-30 Jun       (leaf)  0.221
\-- admin                                (portfolio)
    \-- CVE-F on admin, 1-15 Aug         (leaf)  0.106
```

A mitigation says what it targets by a condition that picks out a set of nodes
(glossary 3.1). The two portfolios under `db` exist so that "every leaf under
`db, firewall working`" picks out the right three leaves. `batch` does not
appear in the tree.

The figures:

| Node | Every open day counted | Inherent risk | Residual risk |
|---|---|---|---|
| `web` | 0.31 | 0.31 | 0.31 |
| `db` | 0.51 | 0.51 | 0.32 |
| `admin` | 0.41 | 0.11 | 0.11 |
| `batch` | 0.29 | no leaf | no leaf |
| Estate | 0.86 | 0.70 | 0.58 |

The step from 0.86 to 0.70 is what reachability is worth, from [R1] and [R2].
The step from 0.70 to 0.58 is what the firewall is worth, on the days [R3] found
it working.

Four things about reachability follow from this stage:

- It is a yes or no about an asset on a day. It is worked out before the tree
  exists, and it shapes the tree. It plays no part inside a trial.
- It carries no probability. The probability comes from the daily rate and the
  days that count.
- A control enters the model in one of two ways. A control that leaves no way
  in, such as the isolation of `batch`, is a fact, and it removes days. A
  control that reduces the rate, such as the firewall, is a mitigation. One
  control entered both ways is counted twice.
- The two ways show up differently. A control entered as a fact is already
  inside inherent risk. The comparison of inherent and residual risk does not
  show its worth. `admin` is the example: its drop from 0.41 to 0.11 is in
  neither column's difference.

---

## Stage 9 — a second kind of reachability, inside the software

Stage 8 asked whether traffic can arrive at the asset. A different question
uses the same word: does the application ever run the flawed piece of code?
Software includes many packages and uses only parts of them. A vulnerability in
a part that is never run cannot be exploited.

A **software bill of materials**, SBOM, is a machine-readable list of the
packages inside one piece of software. CycloneDX is one format for it. The
format has a place for a statement per vulnerability that the flawed code is
present and never run. The statement is optional, and it is usually filled in
by a person or by a separate analysis tool.

In the model this is a fourth yes or no per row, and it works like [R1].
Suppose the SBOM for `web` says CVE-D's code is never run. Row 3 then produces
no leaf. The estate's residual figure moves from 0.581 to 0.575, a small step
because CVE-D's score is low.

An SBOM does not say whether traffic can arrive. One piece of software deployed
in two places has one SBOM and two different answers to that.

---

## Stage 10 — two sources that disagree

So far each fact came from one source. In practice facts come from several: a
network diagram, the firewall's configuration, a scan, the SBOM. They can
contradict each other.

Take `batch`. Stage 8 took "no way in from the internet" from the network
diagram. Now a scan run on 15 May reports that `batch` answered from the
internet.

The rules of stage 8 cannot use both statements. Rules of that kind assume the
facts agree. `Argumentation-Engine.md` describes a method for facts that do
not. Its three terms:

- An **argument** is a claim together with what supports it.
- One argument **attacks** another when both cannot hold.
- An argument is **defeated** when an argument that attacks it is accepted.

The claims about `batch`:

```
A   batch has no way in from the internet        (source: network diagram)
B   batch answered from the internet on 15 May   (source: scan)
```

A and B attack each other. A ranking of sources settles which one stands.
Suppose the ranking says a scan outranks a diagram. Then B is accepted and A is
defeated. `batch` was reachable from 15 May until row 8 was fixed on 25 May.
That is 10 days:

```
leaf: CVE-C on batch, 15–24 May     likelihood 0.157
Estate, residual risk               0.58 becomes 0.65
```

Now add a third claim:

```
C   the scan of 15 May was run from inside the network   (source: scan record)
```

C attacks B, because a scan from inside says nothing about the internet. Nothing
attacks C, so C is accepted. B is then defeated. A is no longer attacked by
anything accepted, so A stands again. `batch` has no leaf, and the estate is
back at 0.58.

The method works through such chains by one procedure: accept every argument
nothing attacks, defeat what those attack, accept what is now unattacked, and
repeat until nothing changes.

Its result is still a yes or no per asset per day. Everything after the
reachability step stays as it was in stage 8. The scratch note states when this
method is needed: it is "Justified when the model contains genuine *defeat*, not
merely absence-of-mitigation." With one source per fact, the rules of stage 8
are enough.

---

## Stage 11 — one asset attacked through another, and why that does not fit

Stage 8 fixed where the attacker starts: the internet. That starting point is
the same on every day and in every trial, so reachability was a fact about
configuration.

There is another reading of `db`. Suppose `db` cannot be got to from the
internet at all, and only from `web`. An attacker then has to take over `web`
first. This is an **attack chain**. The logic note's rule for it starts from an
asset that is already compromised:

```prolog
exposed(A) :- compromised(C0), reachable(C0, A), has_vuln(A, V), not has_mitigation(V, _).
```

Under this reading, whether `db` is reachable is no longer a fact about
configuration. It depends on something that happens inside a trial: whether a
`web` leaf came up "it happened".

Compare the two readings with the stage 8 figures, `web` at 0.31 and `db` at
0.32, and leave the order of the dates aside:

| | `db` is exploited in this share of trials | of which `web` was not exploited |
|---|---|---|
| The tree as built, leaves independent | 32% | 22% |
| Attack chain, `db` only after `web` | 10% | 0% |

The tree as built records `db` losses in 22% of trials where, under the chain
reading, they could not have happened.

The register cannot express the chain today. Per-trial addition treats the
leaves as independent, and `docs/scratch/DEPENDENCE.md` records the state: "The
current tool does not model cross-risk dependence at all: leaves simulate
independently". Everything up to stage 10 stays inside that limit, because it
only ever asks about a way in from a fixed starting point.

---

## Stage 12 — what the finished tree is used for

**What one control was worth.** The register can value the tree with any chosen
set of its mitigations applied. The glossary calls such a set a **selection**
(4.5). Valuing the tree with the firewall alone and comparing against inherent
risk gives the firewall's worth for the year: 0.70 against 0.58 for the estate,
and the same comparison for losses.

**Which control to add.** A control that does not exist yet is added to the same
tree as a mitigation and valued the same way. The answer reads "had this been in
place last year, it would have removed this much." Using that to choose rests on
one assumption: next year's vulnerabilities resemble last year's.

**Joining the technical view with the business view.** The technical data
supplies the likelihood of each leaf. It supplies no loss distribution. People
who know the business supply that, as in stage 3. They also supply the risks
that have no row in any life table, such as fraud or a supplier failing, as
leaves written by hand beside the generated ones.

One thing must be kept apart when the two are joined. A hand-written leaf
"customer database breach, likelihood 0.2" and generated leaves for the
vulnerabilities on that database describe part of the same event. Both under
one portfolio add the losses twice. Stage 20 shows how to join them.

---

# Part 2 — where the numbers come from, and how sure we are of them

Part 1 used every number as if it were known. Part 2 takes the numbers one at a
time. For each it asks three things: where the number comes from, how to state
that we are not sure of it, and what the simulation does with that statement.

The method in stages 15 to 17 and the checks in stage 21 follow Richard
Seiersen's book *The Metrics Manifesto*. The book's example code is in
`~/projects/R/TMM`, outside this repository. The use of EPSS's daily scores at
the end of stage 17 is not from the book. Its code does not use EPSS.

Part 2 uses terms from statistics. Each is named where it is first used. The
glossary has an entry for none of them.

---

## Stage 13 — a likelihood rests on three inputs

Every leaf's likelihood in Part 1 came from one formula:

```
likelihood = 1 − e^(−daily rate × days that count × share of the rate a control leaves)
```

So three inputs stand behind every likelihood. Each came from a different kind
of source:

| Input | Where Part 1 took it from | Kind of source |
|---|---|---|
| Days that count | The life table's dates, and the reachability derived in stage 8 | Facts that were recorded or derived |
| Daily rate | The EPSS score | A model that somebody else fitted to data about the whole world |
| Share a control leaves | "The firewall stops 90%" | A judgement. In Part 1 it is invented for the example |

A fourth kind of source did not appear in Part 1: our own counted records, such
as how many attacks were seen in how many days.

The first input can be wrong when sources disagree. Stage 10 dealt with that,
and its answer stays a yes or no. The other two inputs are numbers, and a number
can be stated with more or less confidence. Stage 14 starts with the weakest of
the three, the control's share.

---

## Stage 14 — the firewall again: what a figure such as 90% can mean

A firewall decides by rule. For the traffic that exploits one particular
vulnerability, it either has a rule that matches or it has none. So for CVE-B it
blocks every attempt or it blocks no attempt. "Stops 90% of attack attempts"
describes no real device.

A figure between 0 and 1 can still be right. It then describes what we know
about the firewall. For one firewall and one vulnerability there are three
cases:

| Case | What it means for the row | How it enters the model |
|---|---|---|
| Tested, and the firewall blocked it | No attempt arrives | A fact, like [R1]: the row has no day that counts |
| Tested, and the firewall did not block it | The firewall changes nothing | The row counts in full |
| Not tested | We do not know which of the two holds | A belief, stated as a number |

A test here means sending the exploit's traffic at the firewall and seeing
whether it passes. The firewall's own log cannot replace the test. It records
what the firewall stopped. It has no record of traffic it let through because it
did not recognise it.

Take the third case. Suppose we are 90% sure the firewall blocks CVE-B. Call the
row's likelihood without the firewall `p`. Two things can be true, and each has
its own likelihood:

```
likelihood = 0.9 × 0  +  0.1 × p  =  0.1 × p
```

This is the **law of total probability**: the chance of an event is the average
of its chance in each case, weighted by how likely each case is.

For row 5 over 60 days that gives `0.1 × 0.19 = 0.019`. Stage 4 computed 0.021
by reducing the rate, and noted that the register's relative statement gives
0.019. Under this reading the relative statement is the exact one. Part 2 uses
this reading. For the stage 8 estate the residual figure moves from 0.581 to
0.579.

Now suppose two of the three vulnerabilities behind the firewall were tested:

| Leaf | Test result | Likelihood without the firewall | Likelihood with it |
|---|---|---|---|
| 4 CVE-A on `db` | blocked | 0.023 | no leaf |
| 5a CVE-B on `db`, 2–31 Mar | not tested, 90% sure it is blocked | 0.100 | 0.010 |
| 6b CVE-E on `db`, 1–19 Jul | not blocked | 0.202 | 0.202 |

| Estate | With the 90% of Part 1 | With these test results |
|---|---|---|
| Inherent risk | 0.70 | 0.69 |
| Residual risk | 0.58 | 0.66 |

The firewall's worth for the year falls from 0.12 to 0.03. The test showed that
it does not block the vulnerability with the highest score. The blocked
vulnerability now sits inside inherent risk, as stage 8 said of every control
that enters as a fact.

Statistics has names for the two kinds of not knowing that are now in the
model. **Aleatory uncertainty** is chance in the event itself: the coin of
stage 3. **Epistemic uncertainty** is not knowing a figure: the 90%. A test
removes epistemic uncertainty. Nothing removes the coin.

---

## Stage 15 — three ways to state a number we are not sure of

Stage 14's figure is easier to work with turned round. The **pass share** is
the share of vulnerabilities whose exploit traffic the firewall lets through.
For a vulnerability that was not tested, the pass share is the chance that the
firewall does not block it. Stage 14 used a pass share of 0.10.

A number such as the pass share can be stated in three ways. Each one says more
than the one before.

**As a point.** "The pass share is 0.10." This says nothing about how sure we
are.

**As a range.** "My middle estimate is 9%, and I am 90% sure it is below 19%."
This is a distribution over the pass share itself. For a number between 0 and 1
the usual choice is the **beta distribution**. It has two parameters, `a` and
`b`. They read as counts: a belief that is worth having seen `a` vulnerabilities
pass and `b` blocked.

```
how plausible a pass share x is:   proportional to  x^(a−1) × (1−x)^(b−1)
mean                               a / (a + b)
```

The statement above fits `a = 2`, `b = 18`:

| Beta(2, 18) | |
|---|---|
| Mean | 0.10 |
| Middle value (median) | 0.087 |
| 90% sure it is below | 0.19 |
| 90% sure it is between | 0.02 and 0.23 |

Seiersen's function `GetBeliefsHits` in `manifesto_functions.R` does this
fitting. It takes the middle estimate and the "90% sure below" figure and
returns `a` and `b` through `beta.select`.

**As a range updated by data.** Now 100 vulnerabilities are tested against the
firewall, and 4 pass. **Bayes' rule** says how to change the belief:

```
updated belief in x   is proportional to   starting belief in x  ×  chance of this data if x were true
```

If the pass share were `x`, the chance of seeing 4 pass and 96 blocked is
proportional to `x^4 × (1−x)^96`. Multiply that into the starting belief:

```
x^(2−1) × (1−x)^(18−1)  ×  x^4 × (1−x)^96   =   x^(6−1) × (1−x)^(114−1)
```

The result is again a beta distribution, with `a = 2 + 4 = 6` and
`b = 18 + 96 = 114`. So the whole update is two additions: add the passes to
`a` and the blocks to `b`.

| | Starting belief, Beta(2, 18) | After the test, Beta(6, 114) |
|---|---|---|
| Mean | 0.10 | 0.05 |
| 90% sure it is between | 0.02 and 0.23 | 0.02 and 0.09 |

The new mean is a weighted average of the judgement and the data. The
judgement was worth 20 observations and the test supplied 100:

```
0.05  =  20/120 × 0.10  +  100/120 × 0.04
```

So `a + b` is how much the judgement weighs, counted in observations. A
confident expert states a narrow range, which gives a large `a + b`, and the
data then moves the figure less.

Statistics calls the starting belief the **prior** and the updated belief the
**posterior**. A starting belief that keeps its form under the update, as the
beta does here, is called **conjugate**. The term "chance of this data if x
were true" is called the likelihood function. This note does not use that
name, because likelihood here always means the glossary's: the chance that a
risk's event happens.

---

## Stage 16 — which single number the leaf receives

A leaf holds one likelihood. A beta distribution holds many possible values. The
leaf receives the **mean**.

The reason is the law of total probability from stage 14. Imagine each trial
first drew a pass share from the distribution and then flipped the coin with it.
The coin would come up yes in a share of trials equal to the average of all the
drawn values. That average is the mean. So flipping with the mean every time
gives the same count of yes.

For leaf 6b, with CVE-E now taken as not tested:

```
with the mean, 0.05                 0.05  × 0.202 = 0.0101
with the most likely value, 0.042   0.042 × 0.202 = 0.0086
```

The most likely value gives a figure 15% too low. It differs from the mean
because the distribution is lopsided: it has a long side toward higher values,
and the mean counts them.

A second rule applies when the uncertain number sits inside a formula. Apply
the formula to each possible value, then average. Do not average first.

Take a rate that gives, with equal chance, an expected 0.1 events or an
expected 2.9 events over the period. The likelihood is `1 − e^(−expected
events)`:

```
average first:   1 − e^(−1.5)                         = 0.78
formula first:   (0.095 + 0.945) / 2                  = 0.52
```

The correct figure is 0.52. The general result is **Jensen's inequality**: for
a curve that bends downward, the average of the results is below the result of
the average. The curve `1 − e^(−x)` bends downward, because a second event in a
period adds nothing to "at least one".

For the pass share the two orders agree. The leaf's likelihood is the pass
share times `p`, which is a straight line.

---

## Stage 17 — the same method for a rate

The daily rate is a positive number with no upper end, so the beta distribution
does not fit it. The matching distribution is the **gamma distribution**. Its
two parameters read as counts again: a belief worth having seen `k` events in
`t` days.

```
mean rate                      k / t
update with new records        add the events to k, add the days to t
likelihood over d days         1 − ( t / (t + d) )^k
```

The third line is the second rule of stage 16 carried out exactly: the formula
`1 − e^(−rate × d)` applied to every possible rate, then averaged.

Take CVE-B. EPSS gives a rate of 0.00351 per day. Stage 2 used it as a point.
Here it is the starting belief, and it needs a weight. Say it is worth 100 days
of our own observation. That weight is a judgement, invented for this example.

```
starting belief     k = 0.351,  t = 100       mean rate 0.00351 per day
likelihood, 60 days                            0.15
```

The likelihood is 0.15, where the point gave 0.19. A belief worth only 100 days
is a wide one, and the downward bend of stage 16 shows.

Now our own records. Suppose vulnerabilities with a similar score were open and
reachable on our assets for 900 days in total, and no exploitation was seen:

```
updated belief      k = 0.351,  t = 1 000     mean rate 0.00035 per day
likelihood, 60 days                            0.020
```

Nine hundred quiet days outweigh a belief worth 100 days. The likelihood for
row 5 falls from 0.15 to 0.020.

This update rests on one assumption: an exploitation would have been noticed.
An exploitation nobody detected is counted here as a quiet day, and it pulls the
rate down wrongly.

Seiersen's code does this update in `chapter_five_scripts.R`. Statistics calls
the pair the gamma-Poisson model, after the **Poisson distribution**, which
describes the count of events at a constant rate.

**EPSS as a starting point, improved in two ways.** Every figure derived from
the EPSS score so far is an approximation. Two improvements are possible, and
they correct different things.

The update above corrects the size of the rate. It uses our own observations:
the events seen and the days of exposure. The days of exposure are the days
that count from stage 8, so the life table and the reachability step supply
them. The events come from our own records.

The second improvement corrects how the rate develops over a row's life. Part 1
took one score per row and kept the rate the same on every day. EPSS publishes
a new score every day and keeps an archive. Each day's score gives that day's
rate by the formula of stage 2, and the likelihood uses their sum:

```
likelihood = 1 − e^(−(r₁ + r₂ + … + r_days))
```

Suppose CVE-B's score had risen from 0.10 to 0.40 on 1 April, half-way through
row 5, because an attack tool was published. The rise is invented for this
example.

```
score 0.10 on all 60 days                      sum of rates 0.211    likelihood 0.19
score 0.10 for 30 days, then 0.40 for 30 days  sum of rates 0.616    likelihood 0.46
```

This needs no data of our own. It also handles the clustering stage 2 named:
the days after an attack tool is published carry a higher rate.

The two improvements can be combined. The daily series stays as EPSS publishes
it, and the update estimates one factor `f` that scales the whole series to our
estate. The rate on day `i` is then `f × rᵢ`. The belief about `f` is a gamma
distribution with `k = t`, so its mean is 1, which means EPSS taken as it
stands. The update adds the events seen to `k`. It adds to `t` the sum of the
EPSS rates over the days of exposure, which is the number of events EPSS
expected on those days. So `t` counts expected events instead of days.

```
mean factor                    k / t
likelihood over a row          1 − ( t / (t + sum of the row's daily rates) )^k
```

With the weight used above, a belief worth 100 days at CVE-B's rate is worth
0.351 expected events, so `k = t = 0.351`. The 900 quiet days at a score of
0.10 add 3.16 expected events to `t`:

```
updated belief      k = 0.351,  t = 3.51      mean factor 0.10
row 5, score 0.10 on all 60 days               likelihood 0.020
row 5, score rising to 0.40 on 1 April         likelihood 0.055
```

The first figure is the one the update gave above. The constant score is the
case where the two methods coincide.

---

## Stage 18 — what the range is for, and one figure shared by several leaves

Stage 16 handed the leaf the mean. Three things the range holds do not reach the
leaf.

**How well founded the figure is.** A leaf whose likelihood rests on a
judgement alone and a leaf whose likelihood rests on 100 tests look the same in
the tree. The range is the only place the difference shows.

**How far the result could move.** The simulation can be run many times, each
time with one value drawn from the belief. Each run gives its own figures, and
together they form a band. This is called a two-dimensional Monte Carlo
simulation: an outer draw for the figure we are not sure of, and the trials
inside it.

For the stage 8 estate, with all three vulnerabilities behind the firewall
taken as not tested:

| Belief about the pass share | Estate residual risk, at the mean | 90% band |
|---|---|---|
| Judgement alone, Beta(2, 18) | 0.579 | 0.57 to 0.60 |
| After the test, Beta(6, 114) | 0.572 | 0.57 to 0.58 |

The test narrowed the band. The band was narrow before it. The leaves behind a
working firewall carry little of the estate's risk, and the days with no working
firewall carry most of it. So the band says where more data would pay. Here,
more firewall tests would change little.

**That several leaves depend on the same figure.** Leaves 4, 5a and 6b all use
one pass share. If the firewall is worse than believed, it is worse for all
three at once. The leaves are then not independent.

Take two leaves whose likelihood is the same uncertain figure: 0.02 or 0.18,
with equal chance. The mean is 0.10.

| | Two independent leaves at 0.10 | One shared figure |
|---|---|---|
| Both happen | 0.010 | 0.0164 |
| At least one happens | 0.190 | 0.184 |

Each leaf alone still happens in 10% of trials. Both together happen more often
than the tree shows. The excess, 0.0064, equals the **variance** of the shared
figure: the average squared distance of its values from their mean.

So the wider the range of a shared figure, the more the tree understates trials
with several losses. For Beta(6, 114) the variance is 0.0004 and the effect is
small. For a figure nobody has tested it need not be.

This is the limit of stage 11 again. The register simulates leaves
independently, and a figure shared by several leaves is one more way they are
not.

---

## Stage 19 — risks that have no row in a life table

Ransomware, fraud and a supplier failing have no vulnerability row. Their
likelihood comes from three sources, and they are the kinds stage 13 listed:

- the judgement of people who know the business;
- a published figure for firms of the same kind, which plays the part EPSS
  played;
- the organisation's own history of incidents.

The three ways of stating a number apply unchanged, and so does the update.

For a judgement, Seiersen's method asks for a middle estimate and a "90% sure"
figure, as in stage 15, and never for a single point. The register already
takes a loss this way: the two bounds of stage 3 are a 90% range (glossary 1.4).
It takes a likelihood as a point.

A worked case. A published figure says firms of this kind suffer a ransomware
event about once in 10 years. The figure is invented for this example. As a
starting belief it is one event in 10 years, with the gamma distribution of
stage 17 counted in years:

```
starting belief     k = 1,  t = 10 years      likelihood for one year   0.091
```

The organisation's own record shows 8 years with no such event:

```
updated belief      k = 1,  t = 18 years      likelihood for one year   0.053
```

A published figure describes other firms. How much weight it deserves depends
on how much those firms resemble this one, and that weight is a judgement.

---

## Stage 20 — joining the two views by splitting an event into causes

Stage 12 warned that a hand-written leaf and generated leaves can describe the
same event. The way to join them is to split the event by cause, so that no
loss can fall under two leaves.

The business states "customer database breach, likelihood 0.2". The generated
leaves under `db` already describe a breach through a known vulnerability. So
the hand-written leaf is narrowed to every other cause, such as a stolen
password:

```
Customer database breach              (portfolio)
|-- through a known vulnerability     (portfolio)  the generated db leaves     0.32
\-- by any other way                  (leaf)       written by hand             0.08
```

The 0.08 is a judgement, stated and updated as in stage 19. The chance of at
least one breach follows as in stage 5:

```
1 − (1 − 0.32) × (1 − 0.08) = 0.38
```

The business's 0.2 is not entered anywhere. It serves as a check. The two views
disagree, 0.38 against 0.2, and the disagreement has a cause to look for. One
candidate is the simplification of stage 1: an exploited vulnerability is not
yet a breach with a loss. Another is that the business's estimate is low.

The division of work is then fixed. The technical data supplies the likelihood
wherever a row exists. The business supplies the likelihood where none exists,
and the loss distribution everywhere.

---

## Stage 21 — checking the assumptions Part 1 made

Part 1 made three assumptions in passing. Each can be tested against the data,
and Seiersen's code has a test for each.

**The rate is the same every day (stage 2).** At a constant rate, the count of
events per week follows the Poisson distribution, and its variance equals its
mean. So the ratio of the two is about 1. `chapter_five_scripts.R` computes it
for vulnerabilities found per week:

```r
data_ratio <- var(vuln_wks$extreme)/mean(vuln_wks$extreme)
```

A ratio well above 1 means the events cluster. The count then fits a rate that
itself varies from week to week, which is the gamma belief of stage 17. Where
the clustering is in one vulnerability's own rate, EPSS's daily scores follow
it, as the end of stage 17 shows.

**Every row has a fix date (stage 5).** Some rows are still open when the year
ends. For last year's likelihood that does no harm: the row counts up to the
last day. It does harm when the question is how long a vulnerability stays
open. An average over the fixed rows alone leaves out the slowest ones.
Statistics calls an open row **censored**: its duration is known to be at
least a certain length. **Survival analysis** uses that partial knowledge.
`chapter_two_scripts.R` does it:

```r
t_surv <- survfit(data = tmm, Surv(time.diff, status) ~ 1)
```

`status` marks whether the row was fixed or was still open. The result matters
for control selection. A control that speeds up fixing shortens every row, and
its worth depends on how long rows last without it.

**Next year's vulnerabilities resemble last year's (stage 12).** Two periods
can be compared. The difference between them is itself uncertain and has a
range. Before looking, a width is agreed below which a difference does not
matter. That width is called the region of practical equivalence, and
`chapter_four_scripts.R` computes it with `rope(diff_dist)`. If the
difference's range lies inside it, the assumption holds for those two periods.

One more case uses the weighted average of stage 15. An asset with two rows
gives figures that rest on almost nothing. The figure for all assets together
serves as the starting belief, and the asset's own rows update it. An asset with
few rows stays near the figure for all assets. An asset with many rows moves to
its own. `chapter_six_scripts.R` builds a starting belief from earlier data,
under the heading "Empirical Bayes Prior".

---

## Where the facts would come from on Kubernetes

**Kubernetes** is a system that runs software in containers across a group of
machines. This section says which of the example's facts an ordinary cluster
with an SBOM per image can supply, and where the rest would come from. It is
from general knowledge and was not checked against the Kubernetes or CycloneDX
documentation.

### The terms

The cluster's **API** is its interface for reading and changing its state.
Every object in the cluster is read through it. It returns the present state
only.

A **manifest** is a file that describes one or more cluster objects. Keeping
manifests in git and letting a tool apply them is called **GitOps**. Argo CD and
Flux are the two common tools. Git then holds the intended state with a date on
every change. Three limits apply to those dates:

- Git holds intent, not state. A change made directly in the cluster leaves no
  commit, unless the GitOps tool reverts it.
- A commit date is not the day the change took effect. The tool records when it
  applied the change.
- A Helm chart or a Kustomize overlay is a template. It has to be rendered with
  its values before the actual objects can be read.

The cluster's **audit log**, if it is switched on, records every change with its
time and its author, including changes made outside git. A program can also
**watch** the API, which sends it each change as it happens, and store the
changes itself.

The image **digest** is a fingerprint of a container image. A running pod
names the digest of its image, and an SBOM is produced for one digest. The
digest is what joins the two.

### Which object is the asset

A row of the life table is one vulnerability on one asset. A cluster offers
several objects that could be the asset:

- a **pod**, which is one running copy of a container;
- a **workload**, such as a Deployment or a StatefulSet, which manages several
  identical pods;
- a **namespace**, which groups workloads;
- a **node**, which is the machine the pods run on.

The API reports all four. It does not say which one is the asset, and the
choice changes the figures.

Take `web` as a Deployment with three identical pods behind one Ingress. All
three carry CVE-A for the 19 days of row 1:

```
one asset per workload    one row       likelihood 0.013
one asset per pod         three rows    likelihood of at least one   1 − 0.987³ = 0.038
```

The workload reading fits this case. An attacker sees one address, and the
ingress controller passes each attempt to one of the pods, so there is one
target. Stage 1's simplification points the same way: it takes the score as the
chance for one machine that an attacker can get to. Three pod rows would also
meet the problem of stage 18. Three pods with one image and one way in are not
independent.

The workload is not always the right asset. A container that runs privileged
can reach its node. A vulnerability in it then puts every pod on that node at
risk, and the node is the asset for that row. The loss distribution also bears
on the choice. The business states a loss for a service or for the data it
holds, which sits at the workload or above. A row per pod would need a loss per
pod, and nobody states one.

So which object is the asset is a modelling choice. It decides the rows, how
they group into portfolios, and where the business attaches a loss. The mapping
below takes the workload as the asset.

### The example's assets as cluster objects

| Asset | As a cluster object | The fact stage 8 used | Where the fact is read |
|---|---|---|---|
| `web` | A Deployment published by an Ingress or a Gateway route | Faces the internet all year | The Ingress or route object |
| `db` | A StatefulSet behind a Service inside the cluster | A way in exists, and a firewall stands on it | The Service, and the NetworkPolicy objects that do not forbid the way |
| The firewall | A web application firewall in the ingress controller, with rules per vulnerability | Misconfigured from 1 April to 1 July | The rule set's configuration object, and its history in git |
| `admin` | A workload whose management port a temporary Service of type NodePort published | Open to the internet from 1 to 16 August | The Service's creation and deletion, in the audit log or git |
| `batch` | A Job in a namespace with a NetworkPolicy that denies all incoming traffic, and no Service | No way in on any day | The NetworkPolicy |

Two points follow from this mapping.

A NetworkPolicy allows or forbids a connection outright. So it enters the model
as a fact, as `batch`'s isolation did, and never as a mitigation. The firewall
of stages 4 and 14 corresponds to a web application firewall: a filter that
inspects requests and blocks those that match a rule.

The temporary port on `admin` shows why the audit log matters. A Service
created by hand for two weeks may never appear in git. The API shows nothing
after it was deleted.

### Each column of the life table

| What the model needs | Stage | From the SBOM | From the cluster's API | Elsewhere |
|---|---|---|---|---|
| Which vulnerability is in which image | 1, 5 | The packages and versions, matched against a vulnerability database | | An image scanner's report, which does the same match |
| Which asset runs that image | 5, 7 | | The pods and the digests they run | |
| One vulnerability on several assets, such as CVE-A on `web` and `db` | 7 | The same package in two images, often from a shared base image | Which pods run each image | The Dockerfile, which names the base image |
| Found date | 5 | | When the pod with that image started, at present only | The later of the CVE's disclosure date and the deploy date. Deploy dates are in the GitOps tool's history, the CI pipeline's records or the audit log |
| Fixed date | 5 | The fixed version, from the vulnerability database | Which digest runs now | The day the pod with the fixed image replaced the old one, from the same records as the found date. The ticket system records when the fix was requested |
| The EPSS score | 1, 2, 17 | | | The EPSS feed, which publishes one score per CVE per day and keeps an archive. The archive gives a rate per day |
| A way in from outside | 8 | | Services of type LoadBalancer or NodePort, Ingress objects, Gateway routes | The cloud's load balancers and security groups, which are outside the cluster. Their configuration is often in Terraform files in git |
| Which workloads may talk to each other | 8, 11 | | NetworkPolicy objects, and a service mesh's authorization policies if one is installed | Whether the network plugin enforces NetworkPolicy. Some do not, and then the objects have no effect |
| When each of those facts held | 6, 8 | | Nothing: the present only | Git history of the manifests, the audit log, or a program that watches and stores changes |
| Whether the flawed code is run | 9 | The optional statement that it is not, usually empty | | A separate analysis tool, or a person, who fills that statement in |
| A second source on reachability | 10 | | | A network diagram, an external scan such as Tenable's, and the scan's own record of where it ran from |
| Traffic actually seen | 10 | | | Hubble with the Cilium network plugin, or a service mesh's telemetry |
| Whether a control blocks one vulnerability | 14 | | The control's configuration, which says what it should block | A test that sends the exploit's traffic at it, from a penetration test or an attack simulation tool |
| A control's effect where no test was run | 15 | | | A judgement, updated by the tests that were run |
| Own records of exploitation | 17 | | | Incident tickets and the detections of a security monitoring system |
| The days of exposure those records are counted against | 17 | | | No source of its own. The found date, the fixed date and the way in above give the days that count (stage 8), and those are the days of exposure |
| Likelihood of risks with no row | 19 | | | Industry reports, and the organisation's own incident history |
| The loss distribution | 3 | | Labels on namespaces or workloads, if the organisation uses them to mark what data a workload holds | The business, from a business impact analysis or an asset register |

### Files that describe the same components outside the cluster

Some files describe the components without being the cluster's state. They are
useful where the cluster's own records are missing, and weaker where the two
disagree.

- **Dockerfiles** name the base image, the ports an image means to open and the
  user it runs as.
- **docker-compose files** describe services, their ports and their networks.
  They usually describe a development setup. A port open there says nothing
  about the cluster.
- **Helm charts and Kustomize overlays** render to the manifests above.
- **Terraform files** describe the cloud's side: the load balancers and the
  security groups in front of the cluster.
- **CI pipeline definitions and logs** record when an image was built, when its
  SBOM was produced, and when it was deployed.
- **The container registry** holds each image's digest and push date, and often
  the SBOM attached to the image.

### What the cluster and its files do not supply

Which object is the asset is a modelling choice, made as described above.

The rate is not in the cluster, because a rate describes what attackers do and
the cluster records what is deployed and how it is configured. The rate's
starting point is the EPSS feed. The events that update it come from incident
tickets and security monitoring. The cluster's history supplies the other half
of the update: the days of exposure, through the found dates, the fixed dates
and reachability.

A control's effect where no test was run is a judgement. The loss comes from
the business.

---

## What the register does not have for this today

- **The asset facts and the rules.** `Argumentation-Engine.md`, section 8, calls
  them "a build, not an existing capability". A way in that passes through more
  than one asset needs a kind of rule that note says first-order logic, the
  logic the register's query language uses, cannot express.
- **Dates on facts.** The scratch note's facts have none.
- **Dates on a mitigation.** `ASSURANCE-ANCHORED-PARAMETERS.md` names it: "One
  primitive is missing: an expiry date on a mitigation." Stage 6 works round it
  by cutting a row into two leaves.
- **A reduction stated on the rate.** A relative statement multiplies the
  likelihood (stage 4). For the stage 8 estate the difference is 0.579 against
  0.581. Under the reading of stage 14 the relative statement is exact, and
  nothing is missing.
- **The step that builds the tree.** Something outside the register has to read
  the life table, the scores and the derived facts, and write the leaves. With a
  rate that changes from day to day, it reads every day's score from the EPSS
  archive (stage 17).
- **Dependence between leaves.** Stage 11, and the shared figure of stage 18.
- **A range on a likelihood.** A leaf holds one likelihood. The belief it came
  from, and the band of stage 18, have no place in the tree.
- **The update itself.** Turning a judgement and counted records into a mean
  (stages 15, 17 and 19) happens outside the register, before the leaf is
  written.
