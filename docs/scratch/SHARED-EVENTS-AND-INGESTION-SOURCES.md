# Events shared between risks, and what three kinds of input file would feed them

**Status: exploratory scratch. Not a plan, not a decision record, not a
decision.** Written on 2026-11-20 against register 0.25.0, commit `cfe81c91`.
It is a teaching note, written to be read again after some months and
criticised. Every figure in it is invented for the example. Nothing in the
other scratch notes it cites is a settled design either.

---

## 0. How to read this note

**What it is for.** The register simulates each risk separately. Some risks
depend on one event that they share. This note explains the problem that
causes, describes one way to simulate a shared event, and asks what three kinds
of input file would contribute: a software bill of materials, a threat model
and deployment manifests.

**What it does not do.** It does not decide anything. It ends with a task that
is left open: which kind of file to support first.

**The project's words.** `docs/user/TERMINOLOGY.md` is the project's glossary.
A term taken from it is marked with its section, as in "trial (glossary 1.5)".

**This note's own words.** The glossary has no term for the things below. They
are defined here for this note only and are not proposed for the glossary.

| Term in this note | Meaning |
|---|---|
| asset | one thing that can be attacked: a service, a database, a job |
| control | a technical measure that hinders an attack: a firewall, a network rule |
| shared event | something that happens at most once in a trial and that several leaves depend on |
| own event | what happens to one leaf, given that its shared event happened |
| two-coin leaf | a leaf that happens in a trial only when its shared event and its own event both happen |
| source | one kind of input file |

**Where to look first on return.**

| To find | Read |
|---|---|
| What a person still has to estimate or decide | section 12.5 |
| What the model gets wrong even when its inputs are right | section 13, the known limitations L1 to L7 |
| How to work with estimates nobody can verify | section 14 |
| Every assumption | section 15 |
| The questions to attack | section 16 |
| The open task | section 17 |
| What each cited note holds | section 18 |
| How many estimates a real system needs | appendix A |

**Two cautions that apply to every figure in this note.** The model has known
limitations, and the text marks each place one applies, as in "(limitation
L1)". Several inputs are estimates that no file supplies. Section 14 describes
how to find out which of them a decision depends on, and the text points to it
wherever such an estimate first appears.

---

## 1. Where this note goes

It makes eight claims, in this order.

1. The register flips one coin per leaf, and no coin knows about another
   (section 2).
2. That is wrong for leaves that depend on the same event. The tree overstates
   how often something happens and understates how often several things happen
   together (section 4).
3. A two-coin leaf repairs both, and it also covers an asset that can be
   attacked only through another asset (sections 5 and 6).
4. The design needs inputs that are entered by hand today (section 8).
5. Each of the three sources supplies some of those inputs, and none supplies
   all (sections 9 to 11).
6. Sources can disagree. An argumentation graph is one way to settle that
   (section 12.2).
7. The model has limitations that better inputs do not remove (section 13).
8. Estimates nobody can verify can still be worked with: vary each one and see
   whether the decision changes (section 14).

---

## 2. How the register simulates a risk today

A **risk** is an uncertain event with two parts (glossary 1.1). Its
**likelihood** is the chance the event happens in the period being modelled,
usually one year. Its **impact** is how much is lost if it happens, stated as a
**loss distribution** (glossary 1.4): a range, not one figure.

A risk recorded in the register is a **leaf**. A **portfolio** is a node whose
figures come from the nodes beneath it. A root portfolio with everything below
it is a **tree** (glossary 1.2).

The register works by **Monte Carlo simulation** (glossary 1.5). It plays the
year out ten thousand times. One play is a **trial**. In each trial it does two
things at every leaf:

1. It flips a coin weighted by the likelihood. A leaf with likelihood 0.08
   comes up "happened" in about 8 trials in 100.
2. If the coin said yes, it draws one loss from the loss distribution. If it
   said no, the leaf's loss in that trial is 0.

So a leaf records at most one loss per trial, however often its event would
happen in a real year (limitation L1).

The losses of one trial are then added up the tree. The glossary calls this
per-trial addition **combine** (1.7). Every figure the register reports is
counted off the ten thousand totals at each node. The **loss exceedance curve**
(glossary 1.11) is one of them: for each loss, the share of trials that lost at
least that much.

**The property this note is about.** Each leaf's coin uses its own **random
stream** (glossary 1.6): a sequence of numbers, one per trial, that belongs to
that leaf alone. So no leaf's coin is related to any other's. The glossary
states the consequence in 1.8: "Combining this way models risks that are
independent of one another. Two risks that always strike together, or never
together, are not expressed by summing their losses trial by trial."

---

## 3. The example used throughout

A small online shop runs four assets.

| Asset | What it is | Can the internet reach it? | Loss if it is breached |
|---|---|---|---|
| `web` | the storefront | yes | 0.2 to 2 million |
| `api` | the order service | yes, through a web application firewall | 0.5 to 5 million |
| `batch` | a nightly invoicing job | no, a network rule forbids all incoming traffic | 0.1 to 1 million |
| `db` | the customer database | no, only `api` may connect to it | 1 to 20 million |

Two facts about its software:

- `web`, `api` and `batch` are built on the same base image. A base image is
  the set of operating-system packages a container image starts from. A flaw
  in one of its packages is therefore in all three.
- `db` runs a different image.

### 3.1 The vulnerability

A **vulnerability** is a flaw in software that an attacker can use. A published
one has an identifier starting with CVE. The example's flaw in the base image
is called CVE-1.

The **Exploit Prediction Scoring System**, EPSS, publishes one score per CVE
per day. The score is the chance that exploitation of that CVE is observed,
anywhere in the world, within the next 30 days.

*Borrowed from `LIFE-TABLE-LEAVES-AND-REACHABILITY.md`, stages 1 and 2.* That
note turns a score into a likelihood for any number of days. With a score `s`
and a flaw that stayed open for `d` days, the likelihood is
`1 − (1 − s)^(d/30)`. It assumes the chance is the same on every day. The
result can never pass 1, and that has consequences once it is large
(limitation L1).

To keep the arithmetic short, CVE-1 has a score of 0.10 and was open for
exactly 30 days on all three assets. So:

```
chance that CVE-1 is exploited during the days it was open = 0.10
```

### 3.2 What happens to one asset

A CVE being exploited somewhere does not mean every asset that has it is
breached. Say an attack that is under way gets into an exposed asset 8 times in
10. That 0.8 is invented, and no input file supplies it. Section 12.5 lists it
as estimate E1, with everything else that stays with a person. Section 14
shows how to test whether a decision depends on it.

For `web` alone:

```
likelihood of web = 0.10 × 0.8 = 0.08
```

`api` is exposed in the same way until section 7 adds its firewall, so its
likelihood is 0.08 too. `batch` cannot be reached, so it gets no leaf. `db`
waits until section 6.

---

## 4. The problem with one coin per leaf

Enter the two leaves as the register takes them today:

```
Shop              (portfolio)
|-- web           (leaf)  likelihood 0.08
\-- api           (leaf)  likelihood 0.08
```

Each leaf alone is right. Three figures about the leaves together are wrong.

### 4.1 Something happens too often

The chance that at least one of the two is breached follows from the chance
that neither is. With separate coins:

```
chance of neither      = 0.92 × 0.92 = 0.846
chance of at least one = 1 − 0.846   = 0.154
```

But a breach through CVE-1 needs CVE-1 to be exploited, and that has a chance
of 0.10. The tree says 0.154. The error grows with the number of assets. With
ten exposed assets on the base image the tree says 0.57, against a ceiling of
0.10.

### 4.2 Several things happen together too rarely

The chance that both are breached in the same year, with separate coins:

```
0.08 × 0.08 = 0.0064
```

In fact both depend on the same attack wave. In a year when CVE-1 is exploited,
each is breached 8 times in 10:

```
0.10 × 0.8 × 0.8 = 0.064
```

The tree understates the bad year tenfold. That year carries two losses added
together, so it is what the right-hand end of the loss exceedance curve is made
of.

### 4.3 An asset behind another asset cannot be expressed

`db` can be attacked only after `api` has been breached. Whether `db`'s event
can happen depends on what `api`'s coin showed in the same trial. Separate
coins cannot say that. Section 6 works this case.

### 4.4 Where the other notes meet the same limit

*Borrowed.* The life table note meets it three times: for an asset attacked
through another (its stage 11), for several leaves that share one uncertain
figure (stage 18), and for several identical copies of one service (its
Kubernetes section). `DEPENDENCE.md` records the cause: "leaves simulate
independently". `CONTROL-AS-FACT-OR-MITIGATION.md`, Parts 4 to 6, collects the
cases. None of those notes proposes a repair.

---

## 5. The two-coin leaf

### 5.1 The design

A **shared event** is drawn once per trial. Every leaf that names it reads the
same result. A **two-coin leaf** then flips its own coin only in the trials
where the shared event happened.

For the example, one trial runs like this:

1. Flip one coin for the shared event "CVE-1 is exploited this year", with
   chance 0.10.
2. If it says no, `web` and `api` both record a loss of 0.
3. If it says yes, flip `web`'s own coin with chance 0.8, and `api`'s own coin
   with chance 0.8. Each leaf whose own coin says yes draws a loss.

Ten trials could look like this:

| Trial | CVE-1 exploited | `web` | `api` | Loss in the trial |
|---|---|---|---|---|
| 1 to 9 | no | not flipped | not flipped | 0 |
| 10 | yes | breached, 1.1 million | breached, 2.4 million | 3.5 million |

Nine quiet years, and one year in which the losses arrive together.

### 5.2 What changes in the figures

| | One coin per leaf | Two-coin leaves |
|---|---|---|
| `web` breached | 0.08 | 0.08 |
| `api` breached | 0.08 | 0.08 |
| At least one breached | 0.154 | 0.10 × (1 − 0.2 × 0.2) = 0.096 |
| Both breached in one trial | 0.0064 | 0.064 |

Each asset's own figure is unchanged. The figures for the assets together move
in both directions: something happens less often, and the bad year happens ten
times as often.

One figure does not move at all: the mean yearly loss. So whether the design
matters depends on which figure a decision reads (limitation L2).

### 5.3 Why one leaf with a multiplied likelihood is not the same thing

`0.10 × 0.8 = 0.08` is one number, and a leaf can hold it today. That is the
left column above. The multiplication has then happened before the trials, and
each leaf carries its own copy of the 0.10. Two copies are two separate events.
The design keeps the 0.10 as one event that both leaves read.

### 5.4 What stays as it is

- Losses are still added per trial, so combine (glossary 1.7) is unchanged.
- A leaf still records zero or one loss per trial (limitation L1).
- A leaf with no shared event is the leaf of today.

---

## 6. The same design for an asset behind another asset

`db` has its own flaw, CVE-2, in its own image. Only `api` may connect to `db`.
So an attacker reaches `db` only from a breached `api`.

Give `db`'s leaf a shared event of a second kind: **another leaf happened in
this trial**. Here it is "`api` was breached". Say an attacker who holds `api`
gets into `db` through CVE-2 one time in two. That 0.5 is invented.

In each trial:

1. `api`'s event is settled as in section 5.
2. If `api` was not breached, `db` records 0.
3. If `api` was breached, flip `db`'s own coin with chance 0.5.

```
likelihood of db = 0.08 × 0.5 = 0.04
```

Trial 10 of section 5 continues: `api` was breached, `db`'s coin is flipped,
and if it says yes the trial records a third loss, for example 9 million. The
trial's total is then 12.5 million.

**What this gets right.** `db` is breached only in trials where `api` was. With
separate coins and a likelihood of 0.04 entered by hand, `db` would be breached
mostly in trials where `api` was untouched, which cannot happen.

| | `db` as a separate coin at 0.04 | `db` as a two-coin leaf |
|---|---|---|
| `db` breached | 0.04 | 0.04 |
| `api` and `db` both breached | 0.08 × 0.04 = 0.0032 | 0.04 |
| `db` breached while `api` was not | 0.037 | 0 |

**One simplification.** `db`'s leaf here depends on `api` only. It ignores
whether CVE-2 is being exploited in the world at all. A leaf that depends on
both needs a third coin. Section 16, question 1, takes that up.

---

## 7. A control in the two-coin design

A **mitigation** is a risk reduction measure recorded on the tree, apart from
the leaf's own figures (glossary 3.1). That lets the register value a leaf
both ways: **inherent risk** before any mitigation and **residual risk** after
(glossary 4.4).

`api` sits behind a web application firewall. Say it stops 9 attacks in 10.
*Borrowed from the life table note, stage 14:* a firewall decides by rule, so
for one flaw it blocks every attempt or none. The 90% is best read as a belief:
"we are 90% sure it blocks CVE-1". It is estimate E2 of section 12.5, and
section 14 uses it as its worked case.

The firewall acts on `api`'s own coin:

```
api's own chance, inherent     0.8
api's own chance, residual     0.8 × 0.1 = 0.08
```

The register has this form already. A mitigation that multiplies a leaf's
likelihood by a factor is a **relative statement** at the **parameter stage**
(glossary 3.2, 3.6).

The whole example, valued both ways:

| | Inherent risk | Residual risk |
|---|---|---|
| `web` breached | 0.08 | 0.08 |
| `api` breached | 0.08 | 0.008 |
| `db` breached | 0.04 | 0.004 |
| At least one breached | 0.096 | 0.082 |
| `web` and `api` both breached | 0.064 | 0.0064 |

**The line to notice is `db`.** The firewall targets `api` only. `db`'s figure
falls tenfold all the same, because `db` can be breached only in trials where
`api` was. So the firewall's worth includes what it prevents on `db`.

With separate coins this is lost. Somebody enters 0.04 for `db` by hand. The
firewall is then applied to `api`. `db` stays at 0.04, and the firewall's
effect on the customer database, the largest loss in the tree, is not reported.

**A control enters once.** *Borrowed from
`CONTROL-AS-FACT-OR-MITIGATION.md`, Part 1.* A control can enter the model as a
fact that removes exposure, as `batch`'s network rule does, or as a mitigation
that reduces a chance, as the firewall does. One control entered both ways is
counted twice.

---

## 8. What the design needs as input

Every figure and every relation used so far was typed in by hand. Listed by
kind:

| # | Input | In the example |
|---|---|---|
| 1 | Which assets exist | `web`, `api`, `batch`, `db` |
| 2 | Which flaws are on which asset | CVE-1 on three assets, CVE-2 on `db` |
| 3 | Which leaves share an event | `web` and `api` share "CVE-1 is exploited" |
| 4 | The chance of each shared event | 0.10 |
| 5 | Which asset can be reached from where | internet to `web` and `api`; `api` to `db`; nothing to `batch` |
| 6 | Which controls stand where | the firewall in front of `api`; the network rule around `batch` |
| 7 | How well a reducing control works | 90% |
| 8 | The own chance of each leaf | 0.8, 0.5 |
| 9 | Risks that involve no known flaw | none so far, see section 12.4 |
| 10 | The loss distribution of each asset | the fourth column of section 3 |
| 11 | The dates on which each of these held | all taken as "the whole period" |

The three sources below are judged against this table.

---

## 9. Source one: the software bill of materials

**What it is.** A software bill of materials, SBOM, is a machine-readable list
of the packages inside one piece of software, usually one container image.
CycloneDX and SPDX are the two common formats. Matching the list against a
vulnerability database gives the CVEs in that image. The EPSS feed gives each
CVE its score.

**What it adds to the example.** With no SBOM, somebody has to know that `web`,
`api` and `batch` contain the same flawed package. The three SBOMs show the
same package and version in all three images, and a different set in `db`'s
image. That yields:

- CVE-1 on `web`, `api` and `batch`, and CVE-2 on `db` (input 2);
- that the three CVE-1 leaves share one event (input 3);
- the chance of that event, from the score (input 4).

**The problem it solves.** The shared events are found from data. Without an
SBOM, the two-coin design has nothing to tell it which leaves belong together,
and the grouping is a guess.

**What it cannot give.**

- Where an image runs, and whether anything can reach it. *The life table note,
  stage 9:* "One piece of software deployed in two places has one SBOM and two
  different answers to that." From SBOMs alone, `batch` looks as exposed as
  `web`.
- Whether the flawed code is ever run. The CycloneDX format has an optional
  statement for it, and it is usually empty.
- The own chance of a leaf, a control, or a loss.

**In other environments.** An SBOM describes software wherever it runs. For a
virtual machine or a laptop, the installed-package list from the operating
system's package manager serves the same purpose. A vulnerability scanner's
report is the same match already carried out.

---

## 10. Source two: the threat model

**What it is.** A threat model is a hand-written description of a system and of
what can go wrong with it. In this project it is Markdown with one YAML block
per threat (`docs/dev/threat-models/`). It lists:

- **elements**: the processes and stores;
- **trust boundaries**: a boundary encloses elements, and a control decides who
  may pass it;
- **an admits table**: for each boundary, everyone the control lets through,
  and whether that is more than was designed;
- **threat entries**: who, with what prerequisite, can do what, to which asset,
  and which controls stand against it;
- **a status per threat**: `open`, `mitigated-untested` or `mitigated-tested`.

The threats are found with STRIDE, a list of six classes: spoofing, tampering,
repudiation, information disclosure, denial of service and elevation of
privilege.

**What it adds to the example.** A threat model of the shop would hold:

- the four elements, and that `db` is where the customer records are (inputs 1
  and 10, as far as naming whose loss it is);
- a boundary around `db` whose control is "only `api` may connect" (input 5);
- an admits row that records what the control lets through in fact. Suppose it
  says every service in the shop's network can open a connection to `db`. Then
  `web` reaches `db` as well, and `db`'s leaf depends on `web` or `api`, not on
  `api` alone;
- a threat such as "an attacker who controls one service can read the customer
  database". Its prerequisite, "controls one service", is the shared event of
  section 6 in words;
- the firewall's status. `mitigated-untested` says the 90% is a judgement.
  `mitigated-tested` says a test stands behind it (input 7, as far as its
  quality);
- threats that need no flaw at all, such as a database password that is the
  same in every installation (input 9).

**The problem it solves.** It supplies the reasons behind the structure. The
shared event "`api` was breached" exists because a control admits `api` to
`db`. The threat model is where that sentence is written down and checked. It
is also the only one of the three sources that records risks with no CVE.

**What it cannot give.**

- Any rate. A threat entry says what is possible.
- Any loss figure. It names the impacted asset.
- Dates. It describes the system at one commit.
- Scale. Somebody writes it, system by system.

**How a CVE relates to a threat.** This mapping is this note's own. A CVE can
stand in three places:

1. **As an instance of the threat.** The threat says "a flaw in the database
   server would be reachable by these callers". CVE-2 is one such flaw.
2. **As the way a prerequisite comes true.** The threat starts with "an
   attacker who controls `api`". CVE-1 on `api` is one way to get there.
3. **As a way past a control.** A flaw in the firewall itself defeats the
   firewall.

A CVE is weaker evidence than a working attack. Four steps lie between a threat
and a breach: the design permits it; a flaw is known; a method to use the flaw
is public; somebody uses it. A CVE is the second step. The EPSS score estimates
the fourth.

**In other environments.** Other threat-modelling tools save their own formats.
`docs/dev/threat-models/TM-METHODOLOGY-CONCEPTS.md` names Threagile (YAML),
OWASP pytm (Python), and OWASP Threat Dragon and AWS Threat Composer (JSON).
The content is the same kind in each: elements, boundaries, flows and threats.

---

## 11. Source three: deployment manifests

**What it is.** A manifest is a file that describes how software is deployed.
On Kubernetes, a system that runs containers across a group of machines, a
manifest describes objects such as:

- a **workload**: which image runs, in how many copies;
- a **Service** or an **Ingress**: a way in to a workload;
- a **NetworkPolicy**: a rule saying which workloads may talk to each other.

**What it adds to the example.** The shop's manifests would show:

- four workloads, each with its image (input 1, and the link from an asset to
  its SBOM);
- an Ingress for `web` and one for `api`, and none for `batch` or `db`
  (input 5);
- the web application firewall attached to `api`'s Ingress only (input 6);
- a NetworkPolicy around `batch` that denies all incoming traffic (input 6);
- a NetworkPolicy that lets only `api` connect to `db` (input 5);
- that `web` runs as three copies. *Borrowed from the life table note's
  Kubernetes section:* three identical copies behind one address are one
  target, so the workload is the asset and not each copy.

**The problem it solves.** It says which leaves exist at all. `batch` gets no
leaf because nothing can reach it. It says where each control stands. It
supplies the relation "`db` is reached through `api`" from configuration, where
the threat model supplies it from a person's reading. And read every week, it
supplies the dates (input 11): the first reading that shows a change dates the
change.

**What it cannot give.**

- How well a reducing control works. The manifest shows that the firewall is
  deployed.
- Whether a rule is enforced. *The life table note:* some network plugins do
  not enforce NetworkPolicy objects, "and then the objects have no effect".
- The actual state, where it differs from the files. A manifest kept in a
  repository is intent. The running system's own interface reports the state.
- Any rate or loss.

**In other environments.** This table is from general knowledge.

| Environment | Declared intent | Observed state |
|---|---|---|
| Kubernetes | manifests, Helm charts, Kustomize overlays | the cluster's interface and its audit log |
| Docker on one host | compose files | the Docker daemon's own report of each container |
| Cloud infrastructure | Terraform, CloudFormation, Pulumi, Bicep | the provider's interface and its configuration inventory |
| Virtual machines | Ansible playbooks, Puppet manifests | each machine's firewall rules and package list |
| Networks | firewall rule sets, security group definitions | a scan |

---

## 12. The sources together

### 12.1 Which source supplies which input

| # | Input | SBOM | Threat model | Manifests | Still by hand |
|---|---|---|---|---|---|
| 1 | Which assets exist | images only | yes, for modelled systems | yes | |
| 2 | Flaws on each asset | yes | | the image of each asset | |
| 3 | Leaves that share an event | yes, for a shared CVE | yes, as a threat's prerequisite | yes, for reachability through an asset | |
| 4 | Chance of a shared CVE event | yes, with EPSS | | | |
| 5 | Reachability | | as designed and as admitted | as configured | |
| 6 | Where controls stand | | yes | yes | |
| 7 | How well a control works | | whether it was tested | | the figure |
| 8 | Own chance of a leaf | | | | the figure |
| 9 | Risks with no known flaw | | yes | | the likelihood |
| 10 | Loss distribution | | whose loss it is | | the figures |
| 11 | Dates | found and fixed, if read repeatedly | | yes, if read repeatedly | |

Three things can be read off it.

- **No source supplies inputs 7, 8 and 10.** How well a control works, what
  happens to one asset once an attack is under way, and what a breach costs
  stay judgements, or come from tests and from the business. Section 12.5
  lists them in full.
- **Inputs 3 and 5 have more than one source.** That is where sources can
  disagree, and section 12.2 deals with it.
- **The SBOM and the manifests need each other.** The SBOM belongs to an image.
  Only the manifest says which asset runs that image.

### 12.2 When two sources disagree: argumentation graphs

Suppose the threat model, written a month ago, says `batch` has no way in. This
week's reading of the manifests shows a Service that opens a port on `batch`.
One says `batch` gets no leaf, and the other says it gets one.

*Borrowed from `Argumentation-Engine.md` and the life table note, stage 10.* An
**argumentation graph** is a way to reason with statements that conflict. It
has three terms:

- An **argument** is a claim together with what supports it.
- One argument **attacks** another when both cannot hold.
- An argument is **accepted** when every argument attacking it is defeated, and
  **defeated** when an accepted argument attacks it.

The claims about `batch`:

```
A   batch has no way in             (source: the threat model)
B   a Service opens a port on batch (source: this week's manifests)
```

A and B attack each other. A ranking of sources decides such a pair. Say a
reading of the deployed configuration outranks a hand-written document. Then B
is accepted, A is defeated, and `batch` gets a leaf for CVE-1.

Now a third claim arrives:

```
C   the Service is in a test namespace that is not deployed   (source: the deploy record)
```

C attacks B. Nothing attacks C, so C is accepted. B is then defeated. A is no
longer attacked by anything accepted, so A stands again, and `batch` has no
leaf.

The procedure is always the same: accept every argument nothing attacks, defeat
what those attack, accept what is now unattacked, and repeat until nothing
changes.

**A control as an argument.** The same picture fits a control. "CVE-1 on `api`
can be exploited" is an argument. "The firewall blocks it" attacks it. "The
firewall is misconfigured" attacks the firewall's argument and restores the
first.

**With probabilities.** A **probabilistic argumentation graph** gives each
argument a chance of being present. *From general knowledge of the literature
and not checked against the papers:* Li, Oren and Norman (2011) proposed this
form, which Hunter (2013) calls the constellation approach. Every combination
of present and absent arguments is one ordinary graph, and an argument's chance
of being accepted is the total chance of the graphs that accept it. Thimm
(2012), and Hunter and Thimm (2017), developed a different form, the epistemic
approach, in which the probability is a degree of belief in each argument.

Applied to the firewall in the constellation form:

```
E   CVE-1 gets into api           present with chance 0.8
F   the firewall blocks CVE-1     present with chance 0.9
F attacks E
E is accepted when E is present and F is absent:  0.8 × 0.1 = 0.08
```

That is the firewall figure of section 7, derived from a graph.

### 12.3 What an argumentation graph does and does not replace

An argumentation graph answers two questions before the trials run: which
facts hold, and what a leaf's own chance is. Those are inputs 5 to 8.

It does not replace the shared event. Its result is one chance per argument.
Handed to leaves with separate coins, those chances give the left column of
section 5.2 again.

**One distinction decides where a probability belongs.**

- "The threat model is right about `batch`" is a fact we are unsure of. It is
  true or false in every trial alike. It belongs outside the trials.
- "CVE-1 is exploited this year" is chance in the event itself. It differs from
  trial to trial. It belongs inside them, as a shared event.

A constellation graph could be drawn once per trial, and an argument shared by
two leaves would then be a shared event. That is the two-coin design under
another name, and section 16, question 2, asks which description is the better
one to build on.

### 12.4 Risks with no known flaw

A threat model lists threats that no CVE stands for. An attacker may also use a
flaw that is not published, often called a zero-day. Neither has an EPSS score.

*Borrowed from the life table note, stage 20.* The way to hold both kinds is to
split an event by cause, so that no loss can fall under two leaves:

```
api is breached                     (portfolio)
|-- through a known flaw            (leaf)   from the SBOM: CVE-1
\-- by any other way                (leaf)   a judgement
```

"Any other way" covers an unpublished flaw, a stolen password and a
misconfiguration. Its likelihood is a judgement. If both leaves happen in one
trial, `api`'s loss is drawn twice (limitation L4).

For the two-coin design this adds a requirement. `db`'s shared event was
"`api` was breached". With two leaves under `api`, it must read "any leaf under
`api` happened". So a shared event can refer to a portfolio as well as to a
leaf.

### 12.5 What is left for a person to model or estimate

With all three sources in place, two kinds of input remain with a person.
An **estimate** is a figure. A **modelling choice** is a decision about the
shape of the model. No file supplies either kind.

**The estimates.**

| # | Estimate | In the example | Who would know | What could later correct it | If it is wrong |
|---|---|---|---|---|---|
| E1 | A leaf's own chance: how likely this asset is breached once its shared event has happened | 0.8 for `web` and `api`; 0.5 for `db` | whoever handles incidents; nobody with confidence | the organisation's own records: of the times a flaw was being exploited, how often an exposed asset was breached | every asset's figure scales with it, and the chance of several breaches in one trial scales with its square |
| E2 | How well a reducing control works | 90% for the firewall | whoever runs the control | a test that sends the attack at the control, per flaw | the control's worth is misstated, including what it prevents behind the asset, as for `db` in section 7 |
| E3 | The loss distribution of each asset | 1 to 20 million for `db` | the business | past incidents and their cost | every loss figure and the whole loss exceedance curve |
| E4 | The likelihood of a risk with no known flaw | the leaf "by any other way" in section 12.4 | security staff and the business together | a published rate for comparable firms, and the organisation's own history | the tree omits or overstates everything no CVE stands for |
| E5 | How far a worldwide EPSS score applies to this estate | taken as it stands: 0.10 | nobody at the start | own records of exploitation against the days of exposure | every shared CVE event is too frequent or too rare by the same factor |

Each estimate can be stated as a point, or as a range that says how sure its
author is. *Borrowed from the life table note, stages 15 to 17:* a range can be
written as counts, "worth having seen this many hits in this many tries", and
each new record is then added to the counts. That note's stage 17 treats E5 as
one factor on the whole estate, starting at 1.

E1 has no counterpart in the life table note. That note gives every leaf the
full score, which amounts to an own chance of 1.

**The modelling choices.**

| # | Choice | In the example | Why no source settles it |
|---|---|---|---|
| M1 | What counts as one asset | the workload, so three copies of `web` are one asset | a manifest lists copies, workloads, groups and machines, and does not say which is the unit |
| M2 | Which controls are facts and which are mitigations with an efficiency | the network rule around `batch` is a fact; the firewall is a mitigation | both appear in a manifest the same way, as configuration |
| M3 | Which shared event a leaf names where no source shows one | none in the example | a shared password or a shared supplier ties leaves together and is in no SBOM and no manifest |
| M4 | The risk hierarchy: the business events, and where the generated leaves hang under them | `api is breached` as a portfolio split by cause | the sources describe software and configuration, not what the business calls a loss event |
| M5 | Which source wins when two disagree | the manifests outrank the threat model | a ranking is a judgement about the sources |
| M6 | Where the attacker starts | the internet | an insider or a compromised supplier starts elsewhere, and reachability is then a different relation |
| M7 | The period a trial stands for, and how often the sources are read | one year; weekly | neither is recorded in any file |

**What follows for the design.** The two-coin leaf does not reduce the amount
of judgement in the model. It moves the judgement to E1 and makes it visible.
With one coin per leaf the same judgement is made silently, by treating the
score as each asset's own likelihood.

Section 14 describes how to find out which of the estimates a given decision
depends on. Appendix A counts the estimates for a real system.

---

## 13. Known limitations

A limitation here is something the model gets wrong even when every input is
right. It differs from an assumption (section 15), which is a premise that may
be false. The text of this note marks each place a limitation applies.

| # | Limitation | Does section 14's method help? |
|---|---|---|
| L1 | A leaf counts at most one event per trial, so a large likelihood understates | it detects it |
| L2 | The mean yearly loss does not show whether losses arrive together | no. It is a property of the figure read |
| L3 | Figures come in three kinds, and only one kind survives an unknown scale | it sorts figures into the kinds |
| L4 | An asset's loss is drawn once per leaf, not once per asset | no |
| L5 | The tree has no dates | no |
| L6 | Reasoning over facts gives yes or no, and the simulation gives chances | no |
| L7 | Several inputs have no source and stay judgements | yes. This is what it is for |

### L1 — A leaf counts at most one event per trial

**The cause.** A leaf's coin says yes or no (section 2). An event expected
twice a year still gives at most one loss in a trial. The likelihood of "at
least one" is `1 − e^(−rate)`, where the rate is the expected number of events.
That figure flattens as it approaches 1.

**What it does to a comparison.** Suppose every rate in the tree is off by one
unknown factor `f`, for example because a worldwide EPSS score does not fit
this estate (estimate E5). For rare events the factor does no harm to a
comparison. Take rates 0.01 and 0.02:

| | `f = 1` | `f = 3` |
|---|---|---|
| First risk | 0.0100 | 0.0296 |
| Second risk | 0.0198 | 0.0582 |
| Second ÷ first | 1.99 | 1.97 |

"The second is twice as likely" holds whatever `f` is. Now take rates 1 and 2:

| | `f = 1` | `f = 3` |
|---|---|---|
| First risk | 0.63 | 0.95 |
| Second risk | 0.86 | 0.998 |
| Second ÷ first | 1.37 | 1.05 |

Both crowd against 1, and the ratio depends on `f`. The second is still the
larger one, so the order of two single risks survives.

The order of two portfolios does not always survive. Portfolio A is one leaf
with rate 2. Portfolio B is three leaves with rate 0.5 each. Count the expected
number of leaves that happen:

| | `f = 1` | `f = 0.2` |
|---|---|---|
| Portfolio A | 0.86 | 0.33 |
| Portfolio B | 3 × 0.39 = 1.18 | 3 × 0.095 = 0.29 |
| Larger | B | A |

In events per year A is always ahead, 2 against 1.5. The tree puts B ahead at
`f = 1` because A's single leaf cannot count its second event.

**What follows for modelling.**

- **Many rare leaves are sound in this respect.** Their likelihoods add up
  almost in proportion to their rates, and a common factor cancels in any
  comparison.
- **A frequent event should not be merged into fewer leaves.** Merging makes
  the cap worse. Portfolio A above is the merged case.
- **A frequent event can be split into rarer pieces.** An event expected twice
  a year, entered as twelve monthly leaves, counts 1.84 events a year. As one
  leaf it counts 0.86. A split by asset, by flaw or by period does the same.
  The life table note cuts a row at the day a control's state changed, which is
  such a split.
- **Where no split exists,** the leaf's loss has to stand for the period's
  total. *Borrowed from `LIKELIHOOD-FROM-RATE.md`, section 3.* That note takes a
  likelihood of about 0.18, once in five years, as the line between rare and
  frequent.

Most likelihoods derived from EPSS scores are small, so the limitation applies
to a minority of leaves. The splits above are this note's own reading.

### L2 — The mean does not show whether losses arrive together

Take `web` and `api` at 0.08 each, and say every breach costs 1 million. Play
100 years:

| | One coin per leaf | Two-coin leaves |
|---|---|---|
| Years with exactly one breach | 14.7 | 3.2 |
| Years with two breaches | 0.6 | 6.4 |
| Breaches in all | 16 | 16 |
| Mean yearly loss | 0.16 million | 0.16 million |

Both designs produce 16 breaches. They differ in how the 16 are spread over the
years. The mean only totals them.

So a decision read off the mean yearly loss comes out the same with one coin
per leaf, with one exception: an asset behind another asset (section 6), where
a control changes the asset's own figure.

A decision read off any of these depends on the spread, and so on the design:

- the right-hand end of a portfolio's loss exceedance curve;
- the drop in the tail. `docs/scratch/MITIGATION-ROI.md`, section 5, prefers
  that figure and heads the section "The aggregate that actually matters:
  ΔTVaR at the portfolio node". TVaR is the mean loss of the worst trials;
- a query that compares a portfolio's figure with a loss threshold;
- a cap or a deductible on a portfolio (glossary 3.4), which acts on a trial's
  total.

### L3 — Figures come in three kinds

| Kind | Example | Rests on |
|---|---|---|
| Structure | "a third of the leaves under `db` are unmitigated" | which nodes exist and what targets them |
| Ranking | "the firewall gives more benefit per cost than the rebuild" | the relations between figures. A common error cancels, within L1 |
| Absolute | "the return on this control is 150%"; "the chance of losing 10 million is under 5%" | every estimate, including the losses and the EPSS factor |

The outputs meant as the basis for decisions are mostly of the third kind. A
return on investment subtracts a cost in money from a benefit in money. A query
compares a figure with a fixed threshold.
`KNOWLEDGE-BASE-FOR-ASSURANCE-QUERIES.md` records that a relative criterion
cannot be written today: "A difference or a ratio of two figures cannot be
written in a query."

An absolute figure from an uncalibrated tree reads as a forecast and is not
one. Stating each output's kind beside it is the least that can be done.

### L4 — An asset's loss is drawn once per leaf

The business states a loss for an asset. Every leaf under the asset draws from
it separately. Two leaves of one asset that happen in one trial add the asset's
loss twice. Whether a second breach of one asset in one year costs the full
loss again is not examined in any note.

### L5 — The tree has no dates

A leaf and a mitigation hold for the whole period. A control that worked for
part of the period has to be expressed by cutting a leaf in two, as the life
table note's stage 6 does.

### L6 — Yes or no from reasoning, chances from simulation

The reasoning over facts (section 12.2) ends in yes or no per fact. A fact that
is wrong is wrong in every trial. The simulation's trials do not show that kind
of doubt (section 12.3).

### L7 — Inputs with no source

Section 12.5 lists them. Section 14 is the way to work with them.

---

## 14. Working with estimates nobody can verify

### 14.1 The idea

Several inputs are judgements (section 12.5, limitation L7). "Is this estimate
right?" cannot be answered: there is nothing to check it against. A second
question can be answered by running the model: **does the decision change if
the estimate is wrong?**

If the decision is the same across the whole plausible range of an estimate,
the estimate does not need to be better. If the decision changes inside the
range, that estimate is worth a test or an expert's time, and the point where
it changes says how precise it must be.

This turns expert input into clarification. The technical sources build the
tree. A person is asked only about the figures the decision turns on.

### 14.2 The steps

1. **State the decision as a comparison.** Name the options, the figure they
   are compared on, and the node it is read at.
2. **List the estimates the figure rests on,** from section 12.5.
3. **Give each a plausible range.** The range is a judgement too, and a wide
   one is the safe choice.
4. **Vary one estimate at a time.** Value the tree with that estimate at each
   end of its range and every other estimate at its middle value.
5. **Record whether the decision changed,** and at which value.
6. **Act on the result.** An estimate that changes the decision gets a test or
   an expert. An estimate that does not is recorded as a convention.

### 14.3 A worked case

**The decision.** The shop can afford one of two controls.

- **The firewall** in front of `api`, as in section 7.
- **A faster rebuild** of the base image, so that CVE-1 is open for 10 days
  where it was open for 30. The chance of the shared event falls from 0.10 to
  `1 − 0.9^(10/30) = 0.035`.

They are compared on the drop in the mean yearly loss at the root. To keep the
arithmetic visible, each asset gets one typical loss inside its range: 1
million for `web`, 2 for `api`, 8 for `db`.

**At the middle values.**

```
no control        0.08 × 1 + 0.08 × 2  + 0.04 × 8   = 0.560 million
the firewall      0.08 × 1 + 0.008 × 2 + 0.004 × 8  = 0.128     drop 0.432
the rebuild       0.028 × 1 + 0.028 × 2 + 0.014 × 8 = 0.193     drop 0.367
```

The firewall is ahead.

**One estimate at a time.**

| Estimate | Range tried | Drop at the low end: firewall, rebuild | Drop at the high end: firewall, rebuild | Does the decision change? |
|---|---|---|---|---|
| E2, how well the firewall works | 50% to 99% | 0.240, 0.367 | 0.475, 0.367 | yes, at 76% |
| E1, the own chance of `web` and `api` | 0.3 to 1.0 | 0.162, 0.138 | 0.540, 0.459 | no |
| E5, the factor on the EPSS rate | 0.3 to 3 | 0.134, 0.115 | 1.17, 0.96 | no |
| E3, the loss of `db` | 2 to 20 million | 0.216, 0.210 | 0.864, 0.681 | no, but close at the low end |

**What the table says.**

- The decision turns on one estimate: how well the firewall works. Below 76%
  the rebuild is the better choice. A test of the firewall against CVE-1
  settles it.
- The own chance does not matter here. Both drops are proportional to it, so
  it cancels. This is the estimate with no source (assumption A3). For this
  decision nobody needs to supply it.
- The EPSS factor does not matter here either. The likelihoods are small, so
  limitation L1 does not bite.
- The loss of `db` nearly matters at its low end. That is worth one question to
  the business: could a breach of the database cost as little as 2 million?

Four estimates were open. One needs work.

### 14.4 The same method on the design itself: the bracket

The worked case reads the mean, where the own chance cancels and the two-coin
design does not matter (limitation L2). A decision that reads the tail is
different, and the method applies to it in a special form.

For the tail, the own chance has two extreme readings, and the register can
hold both today:

- **Leaves never fall together beyond coincidence.** That is one coin per
  leaf, today's tree.
- **Every exposed asset falls when the shared event happens.** That is one
  leaf per shared event, with its loss stated as the total over the assets.
  `CONTROL-AS-FACT-OR-MITIGATION.md`, Part 6, describes this shape.

Valuing the tree both ways brackets the tail. If the decision is the same at
both ends, it does not depend on how the leaves fall together, and the two-coin
leaf is not needed for it. If it differs, the bracket has shown that the design
is worth building, and for which decision. Section 16, question 3, proposes
this check before anything is built.

### 14.5 Which limitations and assumptions the method addresses

| Item | What the method does |
|---|---|
| L7, inputs with no source | Reduces them to the few a decision turns on |
| A3, a leaf's own chance has a source | Shows for each decision whether a source is needed at all |
| A1 and E5, the EPSS figure fits the estate | Varying the factor shows whether the order of options depends on it |
| L1, large likelihoods | A decision that changes when the factor is varied points at the leaves near 1 |
| L3, three kinds of figure | A figure that keeps its order under every variation is a ranking. One that does not is absolute |
| A10, the dependence matters numerically | The bracket of 14.4 tests it |

### 14.6 What the method does not do

- **It varies one estimate at a time.** Two estimates that multiply can each
  look harmless and matter together. A second pass over pairs covers the pairs
  somebody suspects.
- **It tests figures, not structure.** A shared event that is missing from the
  tree is not found by varying what is in it. The modelling choices M1 to M7
  stay open.
- **It needs a decision.** "The curve looks different" cannot be tested. "The
  order of these two controls" can.
- **Its answer holds for that decision only.** The own chance cancelled for the
  mean in 14.3. It does not cancel for the tail.
- **The ranges are judgements.**

### 14.7 What exists for it

- **Scenario branches.** `DEPENDENCE.md` says of them: "fork, modify/remove
  the risk, compare the portfolio node's curve across branches". One variation
  is one branch.
- **Selections.** Valuing the tree with and without a mitigation is a selection
  (glossary 4.5), so the two options of a decision need no second tree.
- **A draft plan.** `docs/dev/SENSITIVITY-ANALYSIS-PLAN.md` describes varying
  "the parameters of one leaf at a time" and ranking leaves by the effect on a
  tree-level figure. Its header says it "Requires redesign before approval". It
  measures how far a figure moves. The method here asks whether a decision
  changes, which is a narrower question.
- **The fuller form.** *Borrowed from the life table note, stage 18.* All
  uncertain figures are drawn together, many times, and the results form a
  band. That covers pairs and costs one full simulation per draw.

The cost of the method here is two valuations per estimate. With the 22
estimates of appendix A that is 44.

---

## 15. The assumptions this note makes

Each is a place where the note can be wrong.

| # | Assumption | Why it may not hold |
|---|---|---|
| A1 | The chance that a CVE is exploited against us is its EPSS-derived figure | The score describes exploitation observed anywhere in the world. The life table note calls taking it for one's own estate a simplification |
| A2 | Once the shared event happened, each asset is breached independently of the others | An attacker inside one asset attacks the next. The own events are then related too |
| A3 | A leaf's own chance has a source | It has none. The 0.8 and the 0.5 are invented, and no source in section 12.1 supplies them |
| A4 | One shared event per leaf is enough | Section 6 already ignores one. Section 12.4 needs "any leaf under a portfolio" |
| A5 | A reducing control multiplies the own chance by a factor | `LIKELIHOOD-FROM-RATE.md`, section 4, shows a factor overstates a control that stops attempts one by one when the event is frequent |
| A6 | The asset is the workload, not each copy | A copy that can reach its machine puts every workload on that machine at risk |
| A7 | Losses in one trial are drawn independently | A wave that breaches several assets may also make each breach worse |
| A8 | Shared events are independent of each other | One attacker may use CVE-1 and CVE-2 in one campaign |
| A9 | A shared event happens at most once per trial | A flaw can be exploited in several waves in one year |
| A10 | The dependence matters for realistic figures | Section 5.2 uses a score of 0.10. With likelihoods of 0.001 the differences are small in absolute terms |
| A11 | The sources are read repeatedly | A single reading gives no dates |

---

## 16. Questions to attack on return

1. **Is this a known formalism under a smaller name?** A set of events, each
   depending on others with a stated chance, is a Bayesian network. Security
   literature calls the same shape an attack graph, and reliability engineering
   has fault trees with common-cause events. This is from general knowledge.
   If leaves need two or three shared events (A4), growing the two-coin leaf
   one coin at a time may end at one of those. Would it be sounder to adopt one
   outright?
2. **Events or arguments?** Section 12.3 shows the two-coin design and a
   constellation graph drawn per trial describe the same thing. Which one
   should the model be stated in?
3. **Does it matter numerically?** Before building anything, the example can be
   simulated both ways outside the register, with likelihoods taken from a real
   SBOM, and the two loss exceedance curves compared. If they differ only
   beyond the last percentile anyone reads, A10 fails and the design is not
   worth its cost.
4. **Where does a leaf's own chance come from?** (A3.) One candidate is the
   organisation's own records, updated as the life table note's stages 15 and
   17 describe. Without a source, the design moves the guess from one figure to
   another.
5. **What does a mitigation target?** In section 7 the firewall acts on the own
   chance. Can a mitigation act on a shared event? Is the shared event a node
   that a query can name and a person can see?
6. **What does inherent risk mean for `db`?** Its figure changes when a
   mitigation on `api` is selected. Glossary 4.4 defines inherent and residual
   risk per node from the mitigations whose target covers that node.
7. **What does the design touch in the register?** At least these:
   - the leaf's shape, which gains a reference to a shared event;
   - the random streams (glossary 1.6). A shared event would be one stream that
     several leaves read. The glossary names sharing a stream as a fault today:
     two leaves that shared a seed "would draw the same numbers every trial";
   - the cache key of a leaf's result, which would depend on the shared event
     as well;
   - incremental re-simulation, where a change to a shared event affects every
     leaf that names it;
   - the sentence in glossary 1.8 about independence.
8. **Is one leaf per shared event good enough?** The register can hold "CVE-1
   is exploited against the shop" as one leaf today, with the loss stated as
   the total over the assets breached.
   `CONTROL-AS-FACT-OR-MITIGATION.md`, Part 6, lists what that loses: the
   firewall is no longer a mitigation the register can value.
9. **Is a control a fact or a mitigation?** Which controls get an efficiency
   (same note, Part 1) is still open and decides what input 6 means.

---

## 17. The open task: which source to support first

This note does not answer it. These are the facts that bear on the choice.

**What each source gives when it is the only one.**

| Source alone | What works | What is missing |
|---|---|---|
| SBOM | the flaws, the shared CVE events and their chances | every asset counts as exposed; `batch` gets a leaf it should not have; no `db` relation; the asset list is by hand |
| Threat model | the structure for the systems somebody modelled, including risks with no flaw | no rates at all; nothing for systems nobody modelled |
| Manifests | the assets, the reachability, where controls stand, the dates | no events and no chances, so a tree with no likelihoods |

**What each source needs from another.**

- An SBOM is tied to an asset through the image, and the manifest holds that
  link.
- The threat model and the manifests overlap on reachability and controls. With
  both, a rule for disagreement is needed (section 12.2).

**How each arrives.**

- SBOMs and EPSS scores come in published, machine-readable formats.
- Manifests are machine-readable, in a different format for each environment of
  section 11.
- This project's threat model is its own format. Its threat entries are YAML.
  Its boundaries and its admits table are Markdown tables with prose in the
  cells.

**Criteria that could decide it.**

- Which missing input causes the largest error in the figures.
- How much stays hand-entered after the source is added.
- Whether the result is usable with that source alone.
- Whether the two-coin design is needed before the source is useful, or the
  other way round.

---

## 18. Further reading

All are scratch notes or working documents. None is a settled design.

| Document | What it holds that this note uses |
|---|---|
| `docs/user/TERMINOLOGY.md` | the glossary: risk, leaf, trial, random stream, combine, mitigation, inherent and residual risk |
| `docs/scratch/LIFE-TABLE-LEAVES-AND-REACHABILITY.md` | a score turned into a likelihood; reachability deciding which days count; a firewall's 90% as a belief; beliefs updated by tests; splitting an event by cause; a Kubernetes mapping |
| `docs/scratch/CONTROL-AS-FACT-OR-MITIGATION.md` | a control counted twice; the attack chain with a fixed factor; one CVE on several assets; a tree grouped by CVE; two methods for a likelihood |
| `docs/scratch/Argumentation-Engine.md` | reachability rules, argumentation graphs, and which engine each needs |
| `docs/scratch/DEPENDENCE.md` | how other fields model dependence between risks; the statement that leaves simulate independently |
| `docs/scratch/LIKELIHOOD-FROM-RATE.md` | a rate turned into a likelihood; when a factor misstates a control |
| `docs/scratch/KNOWLEDGE-BASE-FOR-ASSURANCE-QUERIES.md` | how the query knowledge base works and what new kinds of thing in it would take |
| `docs/dev/threat-models/APPLICATION-TM.md` and `TM-METHODOLOGY-CONCEPTS.md` | a real threat model of this project, and the definitions of boundary, admits table and STRIDE |
| `docs/scratch/MITIGATION-ROI.md` | return on investment for a mitigation: the drop in the mean, the drop in the tail, and a cost |
| `docs/dev/SENSITIVITY-ANALYSIS-PLAN.md` | a draft plan for varying one leaf's parameters at a time |

---

## Appendix A — How many estimates a real system needs

This appendix counts the estimates for this project's own threat model,
`docs/dev/threat-models/APPLICATION-TM.md`, as assessed at commit `cfe81c91`,
stage 6. The deployment counted is `compose-persistent` with the `frontend` and
`observability` profiles. The threat model states the threats and the controls.
The grouping into shared events and loss events below is this note's reading
of its text.

### A.1 What the threat model contains

| Item | Count |
|---|---|
| Running containers | 5: frontend, register-server, Irmin, PostgreSQL, the collector |
| Threat entries | 20 |
| Threats that apply in this deployment | 19. T4 is `not-applicable` |
| Of those, `open` | 18. T5 is `mitigated-tested` |
| Controls listed under the 19 threats | 36 |
| Controls with an implementation named | 12 |
| Controls not built | 24 |

That is about 4 threats per container. The count below follows the threats and
the controls, not the containers.

### A.2 The counting rules

**One leaf per applicable threat.** That gives 19 leaves. None has a CVE, so
none has an EPSS figure.

**Shared events from the prerequisites.** Each threat statement starts with a
prerequisite. The 19 prerequisites fall into about 8 groups:

| Shared event | Threats that start from it |
|---|---|
| A caller reaches a published host port | T7, T8, T9, T11, T12, T13, T23 |
| An attacker controls one container | T19, T20, T21, and the inside path of T7, T11, T12, T13 |
| A workspace key holder misuses the service | T10, T16, T17 |
| Someone reads logs or telemetry output | T5, T14 |
| A party sits on the network path | T6 |
| A registry serves a changed image | T18 |
| Script runs in the SPA's page | T15 |
| A host account passes the daemon's socket | T22 |

**Loss events from the impacted assets.** The statements name the same few
losses repeatedly. Four cover them: customer data disclosed, customer data
altered or destroyed, the service unavailable, and the host taken over.

### A.3 The count with every estimate entered by hand

| Estimate | For ranking only | Two-coin leaves, in money | With a return figure for every control |
|---|---|---|---|
| Chance of each shared event | 8 | 8 | 8 |
| Own chance of each threat leaf (E1) | 0, set to 1 | 19 | 19 |
| Own chance per container for CVE leaves (E1) | 0, set to 1 | 5 | 5 |
| How far EPSS applies (E5) | 0, as it stands | 1 | 1 |
| Loss (E3) | 4 relative weights | 8: two bounds for each loss event | 8 |
| How well the 12 built controls work (E2) | 12, as a class each | 12 | 12 |
| How well the 24 unbuilt controls would work | 0 | 0 | 24 |
| The cost of a control | 0 | 0 | 36 or more |
| **Total** | **24** | **53** | **about 113 to 150** |

- A loss stated on each of the 19 leaves, not once per loss event, is 38
  figures in place of 8.
- A cost is one figure or two: a yearly fee, or an upfront fee with yearly
  maintenance (`MITIGATION-ROI.md`).
- Every estimate stated as a range is two figures.

### A.4 What the technical sources take over

The aim is that the technical sources build the model and a person clarifies.
Two things reduce the 53.

**SBOMs take over two shared events.** Two of the eight depend on a software
flaw: "an attacker controls one container", and "script runs in the SPA's
page", which T15 says comes "through a flaw in the SPA or one of its bundled
libraries". Their chance comes from the CVEs in the images and the EPSS scores.

SBOMs take over nothing else in the count. Most of the 19 threats need no flaw.
T11 needs only that someone reaches Irmin's port. T12 needs only the public
password. Their likelihood is about whether anyone tries, and no technical
source says how often people attack.

**A rule over the threat's status takes over most own chances.** The threat
model already says what stands in an attempt's way. This rule is this note's
proposal:

| The threat's state in the model | Own chance | Threats |
|---|---|---|
| `mitigated-tested` | no leaf | 1: T5 |
| `open`, and no control is built | 1. Whoever reaches it succeeds | 10 |
| a control is built, and the threat is still `open` | a person's figure | 8: T7, T8, T10, T12, T14, T15, T16, T17 |

For the 8, one figure per threat replaces the separate figures for the 12
built controls.

**What stays with a person.**

| Estimate | Count | Can a convention replace it? |
|---|---|---|
| Chance of a shared event with no flaw behind it | 6 | partly. "An internet-facing port is attacked every year" sets one of them to 1 |
| Own chance where a built control stands | 8 | no |
| Loss per loss event, two bounds each | 8 | no. The business states it |
| How far EPSS applies | 1 | yes: taken as it stands |
| Own chance per container for CVE leaves | 5 | yes: set to 1 |
| **Total** | **28** | **22 with the last two conventions** |

### A.5 What the count shows

- **A CVE leaf costs almost no estimate.** Its shared chance comes from EPSS.
  Once the own chances per container and the one EPSS factor are set, each
  further CVE adds nothing. How many CVEs the five images hold is not known
  here. The repository has no SBOM.
- **The estimates sit with the threat leaves.** No source says how often
  somebody attacks, or what a partial control is worth.
- **Grouping keeps the number down.** Without shared events, each of the 19
  leaves needs its own full likelihood. Without loss events, the losses go from
  8 figures to 38.
- **Not all 22 need care.** Section 14 finds the ones a given decision turns
  on. In its worked case that was one of four.
- **The structure needs no estimate.** Which leaves exist, which share an
  event, and what can reach what come from the sources.
