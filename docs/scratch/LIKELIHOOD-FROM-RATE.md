# Likelihood from a rate — "once every X years"

This note explains how to turn an estimate of the form "once every X years" or
"twice a year" into the likelihood a leaf holds, and what goes wrong when the
event is frequent. It is a stub for a user-facing document. Its last section is
a note for implementing a rate as a way to state a likelihood.

Everything here describes the tool as it works now. No part of it needs a
change to the tool.

---

## 1. What a leaf says, and what a trial does with it

A [risk](../user/TERMINOLOGY.md#11-risk) has two parts. Its **likelihood** is
"the chance the event happens in the modelling period, a number between 0 and
1". Its **impact** is the
[loss distribution](../user/TERMINOLOGY.md#14-loss-distribution), the
distribution the loss is drawn from given that the event has happened.

The **modelling period** is the stretch of time every estimate in a tree
assumes, usually one year. The glossary uses the words and has no entry for
them. A leaf does not record the period. It is an assumption shared by every
likelihood and every loss figure in the tree.

A [trial](../user/TERMINOLOGY.md#15-monte-carlo-simulation) is one run of the
experiment, and it stands for one modelling period. At each leaf it does two
things:

1. It decides whether the event happened, with a coin flip weighted by the
   leaf's likelihood.
2. For a leaf whose flip said yes, it draws **one** sample from the loss
   distribution. That sample is the leaf's whole loss for that trial.

So a leaf records zero or one loss per period. It has no way to record that the
same event happened three times in one year. Sections 3 and 4 follow from that
one fact.

## 2. From a rate to a likelihood

A **rate** is the number of times an event is expected to happen in one
modelling period. The glossary has no entry for it. This note writes it as `k`.

| Stated as | Rate `k` per year |
|---|---|
| once in 20 years | 1/20 = 0.05 |
| once in 5 years | 1/5 = 0.2 |
| once a year | 1 |
| twice a year | 2 |

`k` is a count, so it can be larger than 1. A likelihood is a chance, so it
cannot. The conversion between them is:

```
likelihood = 1 − e^(−k)
```

### Where the formula comes from

Split the year into `n` short slices, and give each slice a chance of `k/n` that
the event happens in it. The chance that no slice has the event is
`(1 − k/n)^n`. As the slices get shorter that figure settles at `e^(−k)`. That
is the chance of a year with no event. The likelihood is the chance of a year
with at least one, which is the rest: `1 − e^(−k)`.

The derivation makes two assumptions, and the formula holds only where they do:

- the event is equally likely at any time in the period;
- what happens in one stretch of time does not change the chance in another.

An event that comes in clusters breaks the second assumption. So does an event
that cannot recur once it has happened.

### The figures

| Stated as | `k` | Likelihood to enter | Occurrences in a period that has any |
|---|---|---|---|
| once in 20 years | 0.05 | 0.05 | 1.03 |
| once in 10 years | 0.1 | 0.10 | 1.05 |
| once in 5 years | 0.2 | 0.18 | 1.10 |
| once in 2 years | 0.5 | 0.39 | 1.27 |
| once a year | 1 | 0.63 | 1.58 |
| twice a year | 2 | 0.86 | 2.31 |
| four times a year | 4 | 0.98 | 4.07 |

Section 3 explains the last column.

### When 1/X is good enough

For a rare event, the likelihood is close to `k`, which is 1/X. "Once in 20
years" gives 0.049 by the formula and 0.05 by 1/X. Entering 1/X is fine there.

The two drift apart as the event gets more frequent. At once in five years they
differ by about a tenth (0.18 against 0.2), and this note uses that point as the
line between "rare" and "frequent". Beyond it, 1/X is wrong in two ways:

- "Once a year" as 1/X is 1.0. That claims the event never misses a year. An
  event that happens once a year *on average* still skips some years, and its
  likelihood is 0.63.
- "Twice a year" as 1/X is 2, which is not a likelihood at all.

## 3. Pitfall: a frequent event recorded as one occurrence

**Cause.** A trial draws one sample per leaf (section 1). An event with a rate
near 1 or above happens several times in many of its periods.

Take "twice a year". 14% of years have no occurrence (1 − 0.86). The average
over all years is 2. So the other 86% of years hold all the occurrences, and
each of those years has 2 / 0.86 ≈ 2.3 on average. That is the last column of
the table above.

**Symptom.** A leaf with likelihood 0.86 and a loss distribution that describes
*one* occurrence reports a yearly loss well below the real one. Each trial that
has the event records one sample, where the real year had 2.3 occurrences on
average. Nothing fails and nothing is flagged.

**Fix.** Change what the leaf's two figures stand for. The mechanics stay as
they are: one flip, one sample. For a frequent event the author lets the flip
stand for "was this a period with any occurrences?" and the sample stand for
"what did all of them cost together?".

Two leaves, side by side:

- **A rare event: a data-centre fire, once in twenty years.** The likelihood is
  0.05 and means "the fire happened". The loss distribution is the cost of that
  fire.
- **A frequent event: a phishing compromise, about twice a year, each costing
  10 to 100 thousand.** The likelihood is 0.86 and means "this year had at
  least one compromise". The loss distribution is the year's total for such a
  year. That year has 2.3 compromises on average, so the author states bounds
  for the total, for example 15 to 400 thousand.

"At most once per period" still holds for the second leaf. A year is "a year
with compromises" at most once. The several occurrences sit inside the amount
and not inside a count.

This costs the author a harder estimate. A yearly total is harder to judge than
one occurrence. For a rare event the two are the same figure, and nothing
changes.

### Why not shorten the modelling period

With a month as the period, "twice a year" becomes a rate of 1/6 and a
likelihood of 0.15. That is rare enough for a leaf to hold as one occurrence.
It is not a practical route for a tree already estimated per year, because the
period is shared by the whole tree and recorded nowhere:

- every other likelihood must be converted, with
  `monthly = 1 − (1 − yearly)^(1/12)`;
- every figure that is yearly by nature must be restated, and some cannot be. An
  [insurance policy's](../user/TERMINOLOGY.md#35-deductible-limit-and-the-three-bands)
  yearly deductible and limit have no monthly form;
- every result then describes a month, while the questions asked of a tree are
  yearly.

One twice-a-year risk does not justify that. Keep the year, and state that leaf
as a year's total.

## 4. Pitfall: scaling the likelihood of a frequent event

A [parameter-stage](../user/TERMINOLOGY.md#32-the-two-stages) mitigation changes
a leaf's likelihood in one of two ways
([relative and absolute statements](../user/TERMINOLOGY.md#36-relative-and-absolute-statements)):

- a **relative** statement multiplies the likelihood by a factor greater than 0
  and at most 1: `{"op": "Scale", "factor": f}`;
- an **absolute** statement replaces it:
  `{"replaces": "Likelihood", "probability": p}`.

Multiplying is right for most controls. It overstates the control's benefit in
one case, and the door example shows which.

**A rare event.** Someone tries the door about once in twenty years, so the
likelihood is about 5%. A lock that stops half the tries leaves one successful
try in forty years, about 2.5%. A bad year here contains one try. Stopping half
the tries removes half the bad years. Multiplying 5% by 0.5 gives the right
figure.

**A frequent event.** Someone tries the door about twice a year, so the
likelihood is 86%. The same lock stops half the tries, which leaves about one
successful try a year. A year with one successful try on average is still a bad
year more often than not, at 63%. Multiplying 86% by 0.5 gives 43%. That figure
says the lock removes half the bad years. It removes about a quarter of them. A
bad year here usually contains several tries, and stopping one leaves the
others.

**A control that works or fails for the whole period.** Suppose the lock is
either fitted correctly all year or not at all, with an even chance of each. In
the years it is fitted correctly, no try succeeds. In the other years everything
is as before. Half the bad years disappear, however often someone tries.
Multiplying by 0.5 is right again.

### The figure for the frequent case

Under the two assumptions of section 2, the chance of a period with no event
compounds over time. If a year has no event with chance `1 − p`, then half a
year has no event with chance `(1 − p)^0.5`, because two such halves in a row
make the year.

A control that lets through a share `r` of the attempts leaves attempts arriving
as before, only `r` times as often. A whole period under the control then
carries what a fraction `r` of a period carried before:

```
likelihood after the control = 1 − (1 − p)^r
```

For `p = 0.86` and `r = 0.5`: `1 − 0.14^0.5 = 1 − 0.37 = 0.63`.

This needs each attempt to succeed or fail on its own. Where the control fails
for the whole period at once, the multiplication above is the right figure.

### Which statement to write for which control

**The control works or fails as a whole.** A setting that is in place or
missing, a configuration that masks a secret or does not, a policy that is in
force or lapsed. Write a relative statement. The factor is the chance that the
control is not working in a given period. This holds for a rare event and for a
frequent one.

```json
"spec": {
  "stage": "LeafRelative",
  "likelihood": { "op": "Scale", "factor": 0.05 },
  "severity": { "op": "Keep" }
}
```

**The control stops attempts one by one, and the event is rare.** A filter that
catches most phishing mails, a lock that defeats most tries. Write a relative
statement. The factor is the share of attempts that still get through. For a
rare event this is as accurate as the formula above: at `p = 0.05` and
`r = 0.5` the formula gives 0.0253 and the multiplication 0.025.

**The control stops attempts one by one, and the event is frequent.** The
likelihood after the control is `1 − (1 − p)^r`. That figure depends on the
leaf's current likelihood `p`, so it is a statement about one leaf as it stands
now. That is an absolute statement, and the absolute form is the right one to
write it in:

```json
"spec": {
  "stage": "LeafAbsolute",
  "replacement": { "replaces": "Likelihood", "probability": 0.63 },
  "anchor": "<leaf id>",
  "stamp": { "kind": "KeepIfUnchanged" }
}
```

The absolute form records which leaf the figure was judged against and a
fingerprint of that leaf's content. When the leaf is re-estimated, the statement
is reported as stale until its author works the figure out again and re-affirms
it. A relative factor computed from the same formula would give the same number
today and would go stale without any report, because a relative statement
records nothing about the leaf.

One more step applies when the leaf states the loss as the period's total
(section 3). Fewer occurrences get through, so the total for a bad period falls
too. The control then changes both parts of the risk, and it needs a second
statement for the severity. A relative likelihood paired with an absolute
severity is written as two mitigations, and so is any pair where one half is
relative and the other absolute
([how mitigations compose](../user/TERMINOLOGY.md#part-5--how-mitigations-compose)).

## 5. Summary

| Situation | What to enter |
|---|---|
| A rare event, stated as once in X periods | likelihood 1/X, loss of one occurrence |
| A frequent event, stated as `k` per period | likelihood `1 − e^(−k)`, loss as the period's total |
| A control that works or fails as a whole | relative `Scale`, factor = chance it is not working |
| A control that stops attempts one by one, rare event | relative `Scale`, factor = share getting through |
| A control that stops attempts one by one, frequent event | absolute likelihood `1 − (1 − p)^r`, and a severity statement if the loss is a period's total |

## 6. Note for implementing a rate as an input

Nothing here is decided. These are the facts an implementation has to deal
with.

- **The conversion.** `likelihood = 1 − e^(−k)` for a rate `k` per modelling
  period, with `k` any number above zero. It carries the two assumptions of
  section 2, and a user-facing input has to state them.
- **The range.** The result is always below 1. A leaf's likelihood may be
  exactly 1 today, so a rate cannot express every likelihood a leaf can hold.
- **The period is not recorded.** "Once in 20 years" needs the length of the
  modelling period to become `k`. No leaf or tree holds that length today.
- **What is stored.** Storing only the converted likelihood loses the author's
  statement. Storing the rate changes the leaf's shape and what the cache key is
  computed from. This is an open design choice.
- **The frequent case is not solved by the conversion.** A leaf still draws one
  loss per trial. A rate input that accepts `k = 2` invites a per-occurrence
  loss distribution, which is the defect of section 3. An input has to steer the
  author to a period total there, or the model needs a count of occurrences per
  trial, which it does not have.
- **A relative statement about a rate.** `1 − (1 − p)^r` reads only the leaf's
  current likelihood and one fixed `r`. It stays true when the leaf is
  re-estimated, so it could be a relative form beside `Scale`. Today it can only
  be written as an absolute figure (section 4).
- **Terms the glossary lacks.** Rate, modelling period, occurrence and attempt
  have no entries. A user-facing document that links them needs the entries
  first.
