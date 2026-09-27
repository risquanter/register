# The trial count and the simulation cache key — raw material, not a conclusion

## Read this first

Everything below is hastily constructed argumentation produced inside a
conversation about something else. It is recorded so the thread is not lost,
not because it is trustworthy.

**It has not been checked, and it is not sufficient to decide anything.** In
particular it does not examine the implementation of the content-hash keying
mechanism at all, and it does not consider the design features that mechanism
was built around. Its claims about how the seed identity is handled were read
off a scaladoc comment rather than off the code that implements it. Any of the
observations below may turn out to be wrong, incomplete, or to have been
settled already somewhere this note does not cite.

Before any of this is used, re-derive it against the code, the ADRs and the
design documents.

## The observation that started it

A leaf's simulated result depends on at least three things: the leaf's own
simulation parameters, the workspace seed identity, and the number of Monte
Carlo trials.

`LeafSimResult` stores a `TrialOutcomes`, which carries its own `nTrials`
field, so a cached value records the trial count it was computed at. The cache
key is a content hash of the leaf. Whether the trial count participates in that
key was not established; the keying implementation was not read.

The following were read directly and are the more solid part of this note,
though they still want checking in context:

- `ContentCache.make` builds `Ref.make(Map.empty[ContentHash, LeafSimResult])`,
  so the cache is in memory and does not outlive the process.
- `CachedResultResolverLive` receives one `SimulationConfig`, makes it the
  given, and derives its trial count from it at construction.
- That resolver holds the only call to `RiskResultGroup.create` in `main`, and
  the only construction of a leaf's `TrialOutcomes`.
- `LeafSimResult` appears only in the cache files. No codec and no
  serialization were found for it, so simulation results appear not to be
  persisted anywhere today.

## The question that has no answer yet

If a stored simulation result can ever be read back under a runtime whose trial
count differs from the one the result was computed at, what is the correct
behaviour?

That situation does not arise today, as far as this note established. It would
arise if the result cache became persistent, or if the trial count became
settable per request rather than fixed per process.

## Shapes that came up, recorded without preference

No option here is recommended, and none should be read as leading. Each is
listed with what would have to be examined before it could be evaluated at all.

**A — leave the mechanism as it is and record the precondition in the cache's
own documentation.** To evaluate: whether the precondition as stated is
actually the precondition, which depends on the keying implementation this note
did not read.

**B — put the trial count into the cache key.** To evaluate: how
`ContentHashIndex` derives a key, what the key is meant to cover and why, and
why the seed identity is handled by instance scoping instead. The answer to the
last question probably determines whether B is consistent with the design or
cuts across it.

**C — compare the stored trial count against the runtime's on read and treat a
difference as a miss.** To evaluate: what a silent discard does to the cache's
observability, and whether a read-side comparison is the right layer at all
given how keys are derived.

**D — make the trial count part of the cache instance's scope, alongside the
workspace.** To evaluate: how `ContentCacheRegistry` scopes instances today and
what the seed-identity precedent actually is in code rather than in a comment.

## What a real conclusion would need

1. **Seeding.** How the workspace seed identity reaches a simulation, why it is
   not in the key, and whether the reasoning for that transfers to the trial
   count or does not.
2. **Cache keying.** The actual derivation in `ContentHashIndex` — what goes
   into a key, what deliberately does not, and the stated reason for each. The
   argument above assumed a contract for the key without reading it.
3. **Reading a persisted result under a different trial count.** The real
   question. Whether such a result should be rejected, re-simulated, rescaled,
   or prevented from being stored in a form that allows the mismatch — and
   whether persistence of simulation results is wanted at all.
4. **Whether any of this is already settled.** The ADRs on content equality and
   the cache were not consulted.

## Related

- `docs/dev/TODO.md` item 52, the bounded-by-construction preference and the
  eviction-strategy ADR that does not exist yet. The trial-count question is
  adjacent to it and may belong inside it.
