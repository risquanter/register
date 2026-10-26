# Risk Register Terminology

This is a teaching write-up. It builds the vocabulary first, then the valuation
every figure comes from, then mitigation, then the obstruction mitigation runs
into and how the design resolves it, then the two kinds of sensitive value the
system handles. It assumes no prior knowledge and defines every term at the
point it is first needed.

Throughout, a node's value is shown as a single number, meaning the loss in one
run of the simulation — 1.5 names that run a trial. That is a slice through the
real structure. Everything here applies identically to every other trial,
because every operation described works trial by trial.

---

## Part 1 — The vocabulary, in dependency order

### 1.1 Risk

A **risk** is an uncertain event with two parts, and only these two:

- its **likelihood** — the chance the event happens in the modelling period, a
  number between 0 and 1;
- its **impact** — the loss distribution, meaning the distribution the loss is
  drawn from given that the event has happened.

A risk is recorded as a **leaf** of a tree. A leaf carries four further things:
an identifier, a display name, a link to its parent, and the random-number
stream it draws from. None of those four describes the risk. That distinction
becomes load-bearing at Part 3, because a mitigation can change the two parts
above and nothing else.

### 1.2 Portfolio

A **portfolio** is a node with children. It has no likelihood and no loss
distribution of its own. Its value comes entirely from its children, by the rule
in Part 2.

A **tree** is one root portfolio and everything beneath it.

### 1.3 Loss

A **loss** is the monetary consequence when a risk materialises. Figures are
typically in millions, so 1 means one million.

### 1.4 Loss distribution

The **loss distribution** is the distribution a leaf's loss is drawn from, given
that the event happened. It is what the author states about severity.

The distribution is what the computation uses, not a single number standing in
for it. A single loss estimate — "this event costs 5 million" — keeps one figure
and discards everything else the author knew: how bad the bad case is, how often
the loss is small, how far the tail runs. The distribution keeps all of it, and
carrying it through the arithmetic is what lets a result reflect the whole range
rather than one point chosen out of it.

An author states it in one of two **modes**, and every loss distribution is
exactly one of them.

**Lognormal** is the shorter statement. The author gives two loss bounds, which
are read as the 5th and the 95th percentile — a 90% confidence interval — and a
lognormal curve is fitted through them. The 90% level is fixed by the fitting
method rather than chosen per leaf. The only rule on the pair is that the lower
bound is strictly below the upper.

Say an author states bounds of 500 thousand and 8 million. They are asserting
that if this event happens, the loss falls below 500 thousand one time in
twenty, and above 8 million one time in twenty. The fit fills in everything
between, and the tail beyond.

**Metalog** is the longer statement, and a reader who does not want the
mathematics meets it as the expert estimate. The author gives points on the
curve directly: a 10% chance the loss is under 1 million, a 50% chance under 5
million, a 90% chance under 25 million. Each such point is an **anchor** — a
percentile paired with the loss at that percentile — and a metalog quantile
function is fitted through them.

A metalog statement carries between two and twenty anchors, strictly increasing
in both percentile and loss. Those two bounds come from the fitting method's own
valid range, because a fit cannot use more terms than it has points. The author
may also state how many metalog terms to fit; stating none means one term per
anchor, which is the fit that passes through every anchor the author gave. A
lower term count smooths the curve away from the anchors and is only reachable
by asking for it.

The mode is not a label sitting beside a set of optional fields. It is a choice
between two alternatives, and Part 6 says why that difference matters.

### 1.5 Monte Carlo simulation

A **Monte Carlo simulation** answers a question about uncertainty by running the
same experiment a great many times and counting what came out. One run of the
experiment is a **trial**. How many trials is a configured setting, ten thousand
by default.

One trial visits every leaf and does two things at each, in order.

First it decides whether the event happened. That is a coin flip weighted by the
leaf's likelihood, so a leaf with likelihood 0.3 comes up "happened" in roughly
three trials in ten. A leaf whose flip says no records a loss of zero for that
trial, and nothing further is done with it.

Then, for a leaf whose flip said yes, it draws one sample from that leaf's loss
distribution. The sample is the leaf's loss for that trial.

Take a leaf with likelihood 0.3 carrying the lognormal statement above, bounded
at 500 thousand and 8 million. In trial 1 the flip says no, so the leaf records
0. In trial 2 the flip says yes and the draw returns 2.1 million, so the leaf
records 2.1 million. In trial 3 the flip says yes and the draw returns 240
thousand. Over ten thousand trials roughly seven thousand record a zero, and the
other three thousand record samples spread across the distribution the author
stated.

The losses recorded in one trial are then added together up the tree, trial by
trial, which is the operation 1.6 defines. Doing that for every trial leaves ten
thousand totals at each node, and every figure the system reports is read off
those totals.

That is the whole method behind aggregating losses: a loss either happens or it
does not, a sample stands in for how large it was, and the rest is ordinary
addition performed ten thousand times.

### 1.6 Combine

**Combine**, written `(+)`, is per-trial addition: for each trial, add the two
losses recorded for that trial.

If child A loses 9 in trial 1 and child B loses 14 in trial 1, then `A (+) B`
loses 23 in trial 1.

### 1.7 Monoid

A **monoid** is a set of things plus a way of combining two of them, subject to
two rules:

- **associativity** — grouping does not matter, so `(a (+) b) (+) c` equals
  `a (+) (b (+) c)`;
- **identity** — some element changes nothing, so `a (+) identity = a`. Here it
  is the all-zeros value, a simulation in which nothing ever goes wrong.

Trial outcomes under `(+)` form a monoid. They are also **commutative**: order
does not matter either, because addition does not care about order.

Why this matters in practice: associativity is what licenses computing a
portfolio's children in any order and in parallel. Without it, the order the
children happened to be evaluated in could change the answer.

Combining this way models risks that are independent of one another. Two risks
that always strike together, or never together, are not expressed by summing
their losses trial by trial.

### 1.8 Fold

A **fold** collapses a tree into a single value by giving one rule that says how
a node's value is built from its children's values. The same rule runs at every
node, from the leaves upward. Its formal name is a catamorphism; fold and
catamorphism mean the same thing here.

A fold is defined by two clauses: what to do at a leaf, and what to do at a node
with children.

### 1.9 Homomorphism

A function `f` is a **homomorphism** with respect to `(+)` when it does not care
whether you apply it before or after combining:

```
f(a (+) b)  =  f(a) (+) f(b)
```

A function with this property can be pushed inside a combine freely. A function
without it cannot. This single property is the hinge of Part 4.

### 1.10 Loss exceedance curve

A **loss exceedance curve**, abbreviated **LEC**, is a function from a loss
figure to a probability. Given a threshold, it answers one question: how likely
is a loss at or above this?

It is read off the trials. To find the chance of a loss at or above 5 million,
count the trials whose loss was at least 5 million and divide by the number of
trials. Three hundred such trials out of ten thousand gives 0.03.

A curve therefore also knows the largest and smallest loss any trial produced,
and how many trials it was read from. For display and transmission the curve is
sampled at a fixed number of thresholds, usually a hundred; the sample is a
picture of the function, not the function itself.

---

## Part 2 — The raw valuation

The **raw valuation** is the fold the system has always computed:

```
raw(leaf)      = simulate the leaf
raw(portfolio) = (+) over raw(children)
```

Read the second line aloud: a portfolio's raw value is the combine of its
children's raw values, and nothing else.

That gives a property holding at every node in the tree:

> **Every node's value equals the combine of its children's values.**

The property is not decoration. Two things depend on it.

The first is the cache. Editing one leaf of a large tree should not re-run the
whole simulation, so each leaf's simulated figures are stored under a key
computed from the leaf's own simulation-relevant content — its likelihood, its
loss distribution, and its random-number stream. A portfolio is never stored at
all, because it can always be rebuilt by combining its children. That is sound
only while the property holds. If a portfolio's stored value stopped equalling
the combine of its children, rebuilding it would produce a different figure from
reading it.

The key is computed from what the leaf *is*, not from an event saying it
changed. So a leaf whose likelihood is edited has a new key and misses the cache
by itself, while a leaf that was only renamed or moved keeps its key and its
figures. A renamed risk costs nothing to re-read, and this is why.

The second is construction. An aggregated value cannot be built by handing it a
total; the only way to build one is from the children, and the total is computed
from them. So an aggregate is not merely a figure with children attached. It is
a figure carrying a claim, and the claim cannot be made falsely:

> **my total is the combine of my children.**

Building one can fail for one real reason: summing children's losses can pass
the largest figure the system can represent, and that overflow is reported as a
refusal rather than silently wrapping round to a negative loss. Children that
disagree about how many trials they were run for are a different matter — that
is a programming mistake rather than something an author can cause, and it is
not converted into a refusal.

---

## Part 3 — Mitigation

### 3.1 Mitigation

A **mitigation** is a risk reduction measure, recorded once on the tree and
applied to the nodes it targets. It is held on the tree rather than baked into a
node's own figures: the node goes on describing the risk as it stands, and the
mitigation describes what reduces it.

A mitigation says three things: what it targets, how it reduces, and where it
comes in the order relative to other mitigations.

What it targets is a condition that resolves to a set of nodes, rather than a
hand-listed set. "Every leaf under the Technology portfolio" stays correct when a
leaf is added there.

Because the reduction is held apart from the node, the same tree can be valued
with mitigations applied or without them. Those two valuations are inherent risk
and residual risk, and the same tree can also be valued with any set of its
mitigations in between. Part 4 defines all three terms.

### 3.2 The two stages

How a mitigation reduces is one of two **stages**. They are different mechanisms
rather than two flavours of one thing: they apply to different kinds of node,
they meet the cache differently, and they compose differently.

A **parameter-stage** mitigation changes a leaf's simulation inputs, before the
simulation runs. It changes the two parts of the risk — the likelihood, the loss
distribution, or both — and the leaf is then simulated from the changed figures.

"Two-factor authentication cuts how often this account is compromised to a third
of what it was" is one. The likelihood is multiplied by a third, and every figure
downstream follows from re-simulating the leaf.

Two properties follow from its running before the simulation. It applies to
**leaves only**, because a portfolio has no likelihood and no loss distribution
for it to change. And it changes the **cache key**, because the key is computed
from exactly the figures it rewrites, so a mitigated leaf misses the cache by
itself and no invalidation step is needed.

A **result-stage** mitigation changes a finished figure, after the simulation has
run. It leaves the risk's parameters untouched and transforms the losses the
simulation produced into the losses still borne.

It applies to **any node**, a portfolio included, and that is the point. Capping
a division's total is a statement about the total, and capping each of its leaves
instead gives a different figure. Part 4 shows why.

It never enters the cache. The cache holds the raw simulated figures, and a
result-stage transform is applied to them after they are read back.

### 3.3 The loss borne

Every result-stage operation answers one question, and reading any of them
against the wrong question is the mistake they are written to prevent:

> After this arrangement has paid, how much of the loss is still ours?

A node's figure is always the **loss borne** — what we are still obliged to pay.
It is never what a counterparty pays. The two are easy to confuse, because they
are computed from the same two numbers and they cross at one point.

On a loss of 30 million, against a policy carrying a 5 million deductible and a
50 million limit, the insurer pays 25 million and you bear 5 million. The figure
the system records is 5 million.

The rest of this part is read against that one sentence.

### 3.4 The five result-stage operations

Each one states what is left on your books.

A **cap** says we never bear more than this figure. Everything above it is
covered, with no ceiling on the cover.

A **deductible** says we bear the first slice of every loss and nothing beyond
it. It is the cap read from the other end: the cap keeps the top of a loss off
our books, the deductible keeps the bottom on them.

A **scale** says we bear this fraction of every loss. The rest falls elsewhere,
by transfer or by an agreed share.

A **threshold filter** says losses below this figure are not borne by us at all,
while losses at or above it are borne in full.

An **insurance policy** says we bear the first slice, a counterparty covers the
next band, and anything past the end of that band is ours again.

The first four take one figure each. The policy takes two, and it is the only
one of the five that can describe cover running out — which is why it exists
beside the cap rather than as a special case of it.

A mitigation holds an ordered **pipeline** of these, between one and ten steps.
A pipeline with no steps would have no effect, and the ceiling of ten is chosen
by this project to stop one pipeline restating the same operation over and over.
Order within a pipeline matters, because capping and then scaling is not the same
as scaling and then capping.

### 3.5 Deductible, limit, and the three bands

A policy is described by two figures, and together they divide any loss into
three bands. The first band is the one most often read backwards.

The **deductible** is the first slice of the loss, and it is **yours**. The
policy starts paying only above it.

The **limit** is the most the policy will pay in total. Past it, the policy has
run out.

So for a loss of `L`, with deductible `d` and limit `c`:

```
you bear        =  min(L, d)  +  max(0, L − d − c)
the policy pays =  min( max(0, L − d), c )
```

Read the first line left to right: the first `d` is yours, the policy covers the
next `c`, and above `d + c` it is yours again.

Take a policy with a 5 million deductible and a 50 million limit, and run three
losses through it.

A loss of 3 million falls entirely inside the deductible, so the policy pays
nothing and you bear all 3 million.

A loss of 30 million clears the deductible, so you bear the 5 million deductible
and the policy covers the other 25 million.

A loss of 100 million exhausts the policy. You bear the 5 million deductible,
the policy pays its 50 million limit, and the remaining 45 million comes back to
you — 50 million borne in all.

**A deductible of zero is first-unit coverage.** The policy pays from the very
first unit of loss up to its limit, and you bear only what is above. That is how
an arrangement with no deductible is written: a support contract or a warranty
that absorbs small incidents outright is a policy with a zero deductible and a
limit set to the amount it absorbs.

**A cap is not a policy with a very large limit.** A cap says the cover above it
never runs out, so your loss stops at the cap whatever happens. A policy's cover
does run out, and the loss comes back to you above `d + c`. The two differ
exactly in whether there is a third band.

### 3.6 Relative and absolute statements

A parameter-stage mitigation states its effect in one of two ways, and which one
it is decides what the mitigation has to record.

A **relative** statement says "whatever this leaf is, change it by this much" —
cut the likelihood to 30% of what it was, scale the loss to 60% of what it was.
It stays true when the leaf changes. A control that cuts likelihood to 30% still
means 30% after the leaf is re-estimated, so there is nothing about the leaf's
former figures to record.

An **absolute** statement says "after this control, this leaf *is* these
figures" — the likelihood is 2%, the loss we bear is 1 to 5 million. Those
figures were judged against one particular leaf as it stood when they were
written.

That difference creates a failure mode the relative kind does not have.

The **cause** is that a leaf can be re-estimated after an absolute statement was
written against it. Somebody looking at a leaf of 20 to 120 million and writing
"with the policy we bear 1 to 5 million" has made a statement about 20 to 120
million. Re-estimate the leaf to 200 million to 1.2 billion and the retained
figures are no longer 1 to 5 million.

The **symptom** is a mitigation whose figures describe a leaf that no longer
exists. It still applies, and it reports a figure nobody would now assert.

The **fix** is that an absolute statement must carry two pieces of evidence:
which leaf it was asserted against, and a fingerprint of that leaf's
simulation-relevant content at the time of writing. Comparing the stored
fingerprint against the leaf's current one says whether the statement has gone
stale. A rename or a move does not trigger it, because the fingerprint covers
the simulation-relevant content only — the same content the cache key is
computed from. A relative statement carries neither piece, because it has
nothing to audit.

The two kinds are two alternatives rather than optional fields on one thing, so
an absolute statement missing its evidence cannot be written down at all. Part 5
gives the one consequence that has for what a single mitigation can say.

### 3.7 Precedence

**Precedence** is a figure on each mitigation fixing the order mitigations apply
in when more than one covers the same node. Lower applies first, and the
mitigation's identifier breaks a tie, so the order is total and stays the same
when two versions of a tree are merged.

Three values are named: −1000 applies first, 0 is the default, and 1000 applies
last. All three are chosen by this project rather than derived from anything.

Precedence only changes an answer where two mitigations interact. Two whose
target sets do not overlap commute, and so do two that cover the same node but
change different parts of it.

---

## Part 4 — Valuing a tree with and without mitigation

### 4.1 The obstruction

An aggregate cap is an ordinary requirement: an insurance policy on a division
caps that division's total. Putting it together with Part 2's property produces a
contradiction, and the contradiction is arithmetic rather than a matter of taste.

Take a portfolio whose children sum to 16, carrying a cap at 10. The cap says its
value is 10. Part 2's property says its value equals the combine of its children,
which is 16. One value cannot be both.

The reason is Part 1.9. A cap is not a homomorphism. Take a cap at 10 and two
children that each lose 8 in the same trial:

```
cap10(8 (+) 8)         = cap10(16)    = 10
cap10(8) (+) cap10(8)  = 8 (+) 8      = 16
```

Ten is not sixteen. This is not a shortcoming of the implementation; it is what a
cap means. A cap on a total is a statement about the total, and it is not the
same statement as a cap on each part. A deductible fails the property for the
same reason, and both are ordinary things for an author to write.

### 4.2 The resolution

So a tree is valued twice over. The raw valuation of Part 2 is untouched, and it
is what the cache holds. A second fold — the **mitigated valuation** — runs over
the same tree with one extra step in its rule: combine the children, then apply
this node's own transform.

```
mitigated(leaf)      = f_leaf( simulate the leaf )
mitigated(portfolio) = f_P( (+) over mitigated(children) )
```

`f_X` is the result-stage transform scoped to node `X`, composed from whatever
mitigations target it, and it is the do-nothing function wherever nothing is
scoped.

Here is the whole thing worked through. The figures are the loss in one trial.
An insurance policy caps Servers at 18, and a separate cap of 6 sits on
DiskFailure.

| node | raw | mitigated |
|---|---|---|
| DiskFailure | 9 | `cap6(9)` = 6 |
| PowerLoss | 14 | nothing scoped: 14 |
| Servers | `9 (+) 14` = 23 | `cap18(6 (+) 14)` = `cap18(20)` = **18** |
| Fraud | 3 | nothing scoped: 3 |
| Group | `23 (+) 3` = 26 | nothing scoped: `18 (+) 3` = **21** |

Two things to read off it. The mitigated total at the root is 21 against a raw
26, so the benefit of a policy written two levels down reaches the top of the
tree. And at Servers the mitigated value is 18 while its children's mitigated
values sum to 20: the mitigated valuation deliberately does not have Part 2's
property, and that gap of 2 is the subject of 4.3.

The children must be combined **before** the cap is applied, not after the raw
total has been capped. Capping the raw total gives a different and wrong figure,
because it cannot see a reduction already applied below it. Take a trial where
DiskFailure loses 20 and is capped to 6, and PowerLoss loses 4. Folding the
mitigated children gives `cap18(6 (+) 4)` = 10. Capping the raw total gives
`cap18(24)` = 18. Ten is the right answer, and only the fold reaches it.

### 4.3 Layer

The **layer** at a node is the difference between what that node's own
result-stage transform produced and what it was applied to. It is the amount the
control removed at that level, and it is the figure that says what a policy is
worth.

In the worked example above, the mitigated values of Servers' children sum to 20
while Servers' own value is 18, so the layer at Servers is 2.

A node with nothing scoped to it has an empty layer, and the two figures it is
read from are the same figure.

### 4.4 Inherent risk and residual risk

**Inherent risk** is the loss before any mitigation is applied. It is what every
read returns unless a mitigated valuation is asked for explicitly.

It is not computed by separate machinery. It is the mitigated fold of 4.2 run
with nothing selected, so every node's transform does nothing and every layer is
empty.

**Residual risk** is the loss after every mitigation whose target covers that
node has been applied. The mitigations apply in their recorded order, so residual
risk is the result of running the whole applicable sequence rather than of
applying each one independently.

The two stages enter the fold at different points. A parameter-stage mitigation
rewrites a leaf before it is simulated. A result-stage mitigation transforms a
finished figure after that node's children have been combined.

A tree with no mitigations has identical inherent and residual figures. So does a
tree whose only mitigations have no effect: a scale factor of exactly 1 is a valid
mitigation and changes no figure, and the residual valuation is then the inherent
one rather than something that resembles it.

### 4.5 Selection

A **selection** is the set of mitigations a valuation applies, and each
mitigation in it may be restricted to some of the nodes it targets rather than
all of them. One selection produces one mitigated figure per node: several
simultaneously selected mitigations covering a node compose into a single figure,
not one figure each.

Inherent risk and residual risk are the two ends of that range. Inherent risk is
the empty selection, and residual risk is the selection holding every mitigation
at its full target set. Everything between is also a selection, and it is how a
question about one control on its own is asked.

Say a tree carries three mitigations and an author wants to know what the
backups control is worth by itself. The selection holding that one mitigation,
at its full target set, values the tree with it applied and the other two not.
Comparing that figure against the inherent figure is what attributes a benefit
to that one control.

A restriction narrows a mitigation within a selection. An insurance policy
targeting every leaf under Technology can be selected for two of those leaves
only, which answers what the policy would be worth if it covered just them.

In a screening query a selection is the second argument to a figure. The words
`inherent` and `residual` name the two ends, as in `p95(x, "residual")`. A
mitigation named directly, or bound by a quantifier, names the selection holding
that one mitigation: `p95(x, m)` under `∃m : mitigation` asks the question the
example above asks.

---

## Part 5 — How mitigations compose

A result-stage transform runs on finished figures and always succeeds, so
composing two of them is ordinary function composition: apply the first, then the
second. Doing nothing is its neutral element, and an empty pipeline is how that
is written down.

A parameter-stage transform can fail. Scaling a lognormal's lower bound by a
small factor can push it below the smallest loss the system can represent, and
that has to be reported rather than quietly rounded away. So a parameter-stage
transform takes a leaf's two parts — its likelihood and its loss distribution —
and returns either the changed pair or a refusal.

Two of those compose by running the first and feeding its result into the second
if it succeeded. The do-nothing arrow returns its input unchanged, and that is
what a relative statement of "keep the likelihood, keep the severity" is.

Every parameter-stage mitigation scoped to a leaf is folded through that
composition in precedence order, and the leaf is rebuilt once from the result. A
relative statement about a part it does not name passes that part through
unchanged. An absolute replacement of one part replaces that part and passes the
other through unchanged.

Two consequences follow, and both are relied on.

**A control that is relative in one part and absolute in the other is two
mitigations, and their composition is exact.** No single mitigation says "cut the
frequency to 30% of what it was, and after the policy the loss we bear is 1 to 5
million", because the first half is relative and the second is absolute, and a
mitigation is one or the other. Writing it as two mitigations on the same leaf
produces exactly that result:

```
start                          likelihood 0.40, loss 20M–120M
relative: scale 0.3            likelihood 0.12, loss 20M–120M   severity untouched
absolute: severity 1M–5M       likelihood 0.12, loss   1M–5M    likelihood untouched
```

What that costs is bookkeeping: one authored control becomes two records, so it
has two identifiers, two names and two targeting conditions. What it does not
cost is accuracy. The composed figures are the paired statement's figures rather
than an approximation of them.

**Two mitigations changing different parts of a leaf commute.** In the example
above the relative half reads and writes only the likelihood, and the absolute
half reads and writes only the loss distribution, so running them the other way
round gives the same answer and the precedence figure does not matter. Precedence
matters only where two mitigations change the *same* part: scaling a likelihood
by 0.5 and then replacing it with 0.02 gives 0.02, while replacing and then
scaling gives 0.01.

---

## Part 6 — Alternatives, not optional fields

Two of the things above are a choice between alternatives: a loss distribution is
metalog or lognormal, and a parameter-stage mitigation is relative or absolute.
This part says what the design gains by treating them that way, because the
alternative is tempting and loses information.

A thing that holds several values at once has as many possible states as the
product of its parts' states. Two parts with three values each give nine. A thing
that is one of several alternatives has as many states as the sum of its
alternatives' states. Alternatives of three and four values give seven.

Now write "a circle or a rectangle" as a thing holding several values at once: a
word saying which it is, an optional radius, an optional width, an optional
height. Three optional values admit eight combinations of present and absent, of
which two are meaningful. The other six have to be excluded by a rule written by
hand. That rule can be forgotten, it can drift from the note describing it, and
it has to produce a complaint about a state that should never have been
expressible.

Written as alternatives instead, there are exactly two states and no rule. A
metalog statement has nowhere to put a lognormal bound, and a lognormal
statement has nowhere to put anchors. An absolute statement cannot be written
without its evidence, rather than being written and then refused.

Both were the first form before and are the second form now. In each case the
rule that used to exclude the meaningless combinations no longer exists, because
there is no longer a combination to exclude.

---

## Part 7 — Composing risks, and what not to compose

You combine losses, not curves. Two levels are involved and they are different
operations.

The figures combine through the monoid of Part 1.7, which sums losses trial by
trial. A node is then aggregated by a construction that derives the total from
exactly the children it was handed, as Part 2 describes. Once the figures are
combined, the curve is read off them by the counting procedure of Part 1.10.

A portfolio's curve is **not** the sum of its children's curves. Three reasons,
and the first is enough. A curve's values are probabilities, and adding
probabilities together does not mean anything. Beyond that, a portfolio's curve
has to account for losses landing in the same trial, which only the trial-by-trial
figures record; and whether risks strike together at all is a property of the
figures rather than of the curves read from them.

So the order is fixed: combine the losses, then read the curve.

---

## Part 8 — Sensitive values

Two kinds of sensitive value travel through the system, and they are protected in
**opposite** directions. Treating them as one class is wrong about one of them,
so the vocabulary has to come before any rule about them.

A third kind is defined here as well, although it is not sensitive and is not
protected. It belongs here because nothing in the value itself distinguishes it
from the second kind: the two are the same 26-character string, produced by the
same generator. What separates them is what the server does when a caller
presents one.

### 8.1 Trust boundary

A **trust boundary** is the line a value may not cross without being checked. A
system has several, and they are not neatly nested. The boundary around what the
service itself can see is not the same as the boundary around what appears in a
log file, because a log is read by people and tools the service never talks to:
operators, a log shipper, a dashboard. That difference is what makes the two
kinds below behave differently.

### 8.2 Secret

A **secret** is a value where holding it *is* permission. The workspace key is
the one in this system: a 22-character token drawn from a cryptographic random
source, its length and alphabet fixed by the encoding that source is read
through. Anyone who has it can read and write that workspace until it expires —
after 72 hours, or after an hour of no use. Both limits are this project's
defaults.

Its trust boundary is the narrowest in the system, and two consequences follow.

**It may never be written to a log.** The cause is that a log's audience is wider
than the service. The symptom, if it happens, is a key sitting in plain text in a
file or a dashboard several people and tools can read. The fix is that the value
prints as asterisks rather than its contents, and reading the real value takes an
explicit step, which makes every such place findable.

**It does cross to the client, because that is its purpose.** A credential you
bear exists to be sent. The server returns it exactly twice — when a workspace is
created, and when its key is rotated — and those are the only two answers that
carry it. Where it may never appear is a failure message, which would put it in a
log and in front of a caller at once, and neither needs it.

### 8.3 Confidential identifier

A **confidential identifier** is a value that names something internally but
grants nothing. The workspace identifier is the one here: the key a workspace is
stored under, and the string embedded in the names of its storage branches.

Holding it gives no access at all, because permission comes from the key alone.
So the risk it carries is not that somebody learns a password. It is that
somebody learns a reference they could aim at. That risk has two names.

An **enumeration oracle** is any answer that lets a caller discover which
identifiers exist, by answering differently for one that does and one that does
not. Given one real identifier and a differing answer, a caller can work through
the space and map what is there.

An **insecure direct object reference** is the matching attack: supplying
somebody else's identifier and being served their data, because the server used
the submitted reference instead of deriving the right one from who the caller is.

Both need the caller to see or guess an identifier, so the protection is the
mirror image of a secret's.

**It may be logged.** A reader of a server log gains nothing by seeing it, so
storage paths, commit messages and log lines may all name it.

**It may never cross to the client, in either direction.** Not in an answer, and
not accepted from a request either — the server derives it from the key instead.
Accepting one is an enumeration oracle even when it is never echoed back, because
a request that behaves differently for a valid and an invalid identifier has
answered the question by behaving differently.

### 8.4 Resource name

A **resource name** names one thing inside a scope that has already been chosen.
A tree's identifier is one. So are a node's and a mitigation's.

Reading a tree shows the two stages that make this different from the value
above. A request arrives naming a key and a tree. The server resolves the key to
a workspace first, which fixes the scope. Only then does it ask whether that
workspace contains that tree.

Suppose the caller submits a tree identifier belonging to somebody else's
workspace. It is not among the resolved workspace's trees, so the lookup finds
nothing — the same nothing returned for a tree identifier that was never issued
to anyone. No step in the process distinguishes those two cases, so there is no
difference in the answer, its shape, or its timing to tell them apart.

A resource name is returned to callers and accepted from them. That is not an
exception to the rule just stated for confidential identifiers. It falls outside
that rule, for the reason in 8.5.

### 8.5 Selector and proof

Two jobs are in play, and each of the three values above does a different subset
of them.

**Selecting** is answering which tenant's data a request touches. **Proving** is
establishing that the caller is allowed to touch it.

The workspace key does both. The server looks the key up and gets a workspace,
which is the selecting. That the caller held the key at all is the entitlement,
which is the proving.

The workspace identifier is what that lookup returns. It selects, because every
storage path and branch name is built from it. It proves nothing, because
permission comes from the key alone.

A tree identifier does neither. It names something within a scope that has
already been selected and already been proved.

So the precise statement is this: the key is selector plus proof, the workspace
identifier is selector without proof, and a tree identifier is neither. That is
the asymmetry, and it is not about which values are secret.

Nothing in the values themselves would tell you any of this. The workspace
identifier and a tree identifier are both 26-character sortable identifiers, a
format fixed by the specification they follow, and both come from an ordinary
random number generator rather than a cryptographic one — a deliberate choice,
because an identifier is not a credential. The safety of a workspace identifier
was therefore never its unguessability. Tree and node identifiers come from the
same generator and are returned to callers, so values of that shape and that
quality already cross to the client.

### 8.6 The test that decides confinement

One question decides whether an identifier has to be withheld:

> Does presenting the identifier make the server widen what it looks at?

Run it on a tree identifier. A caller presents one from another workspace. The
server checks it against the trees of the workspace the key already resolved to,
does not find it, and answers as it would for an identifier that does not exist.
The server looked at exactly the same data either way, so nothing widened.

Run it on a workspace identifier. If a request accepted one, the server would
widen as far as the submitted value said, because that value *is* the scope. The
caller would then be choosing the tenant, which is the job the credential exists
to do.

### 8.7 The three side by side

| | a server log | crossing to the client |
|---|---|---|
| **secret** — workspace key | never: the log's audience is wider than the service | yes, in the two answers that mint it; never in a failure message |
| **confidential identifier** — workspace identifier | permitted: holding it grants nothing | never, in either direction |
| **resource name** — tree, node, mitigation identifier | permitted | yes, in both directions |

The one place both sensitive kinds are forbidden is a message a caller can read.
A secret there reaches a log and a caller at once; a confidential identifier
there hands over the reference the rules above exist to withhold.

### 8.8 Why the rule holds when nothing is exploitable today

Holding a workspace identifier gains an attacker nothing at present. The
component that owns workspace records has no lookup that takes an identifier at
all: every way in takes the key. The one lookup that resolved a workspace from an
identifier alone, with no capability check, had no caller and was deleted.

So the rule is not stopping an attack that works today. It is stopping a feature
that has not been written yet, and a worked example shows how.

Somebody is asked to build a page listing the workspaces a person owns. If the
identifier were already in answers, it would be the value nearest to hand, and
the obvious route is a request that names the workspace identifier in its path.
The scope of that request now comes from the path rather than from the key. The
capability check is then absent by construction rather than by oversight, which
is the harder kind of absence to notice when reviewing.

Because nothing returns the identifier today, that author has to add the exposure
first, deliberately, in a change a reviewer can see. The control is on the design
rather than on an attacker.

A resource name cannot become the same trap. No lookup of a tree skips the
workspace, so even a request that named only a tree would still have to resolve a
workspace from somewhere, and the only thing that resolves one is the key.

The same reasoning is what makes a tree identifier free to return in a failure
message. A request that names a tree in its path and carries a query the server
refuses is answered with a message naming that tree. The caller supplied that
value in the request being answered, so the answer adds nothing to what the
caller already held.
