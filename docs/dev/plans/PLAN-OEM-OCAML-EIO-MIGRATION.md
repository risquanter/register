# PLAN-OEM — Optional migration of the register server to OCaml 5 with Eio, with Irmin linked into the server process

**Status:** Optional and unscheduled. This is a record of a direction and its open decisions. It is not implementation-grade and confers no plan coverage: it has no signatures, no inventory document and no verification commands. Those are written if the migration is taken up.
**Written:** 2026-11-14
**Built from:** the user's rulings of 2026-11-13 recorded in `docs/archive/plans/ARCHIVE-PLAN-WSS-WORKSPACE-SEPARATION.md` in the sibling repository `register-infra` (its §1 and §10), and from `private-docs/OCAML5-MIRAGEOS-PORT-FEASIBILITY.md`. That second file is untracked, so every fact this plan needs from it is restated here.
**Decision labels:** `OEM-D-<n>`, numbered from 1 in this document.

---

## 1. Goal

The register server becomes one OCaml 5 program that uses several CPU cores and holds its Irmin store inside its own process. The separate Irmin service, the GraphQL client that talks to it, and the network connection between the two disappear.

The terms the goal uses:

- **OCaml 5** is the OCaml version whose runtime runs code on several cores at once. A **domain** is its unit of parallel execution, and one domain uses one core.
- **Eio** is an OCaml 5 library for input, output and concurrency. Code written against it reads as ordinary sequential code, with no promise type threaded through every signature. It runs on Linux.
- **Irmin** is the store register keeps its trees in. It is an OCaml library. Today register runs it as a separate server and reaches it over GraphQL, through `IrminClientLive` in `modules/server/src/main/scala/com/risquanter/register/infra/irmin/`.
- **Linked into the process** means the server calls Irmin's functions directly. The store's files are still on disk. This is the reading the user confirmed on 2026-11-14. It is not Irmin's memory backend, which keeps nothing.

## 2. Why it is optional

The user's statement of 2026-11-13: "I am not sure about the future of an ocaml port. it is unlcear how much of the benefit it would be to have the implementation in ocaml on a unix binary. with or without eio but with parallelism".

What the migration would buy, each stated as a fact to weigh:

- One process and one image in place of two. The register → Irmin connection is the one connection in the cluster whose server has no TLS option, and `PLAN-TLS-TRANSPORT-SECURITY.md` in `register-infra` carries an open decision, TLS-D-2, on how to protect it. With Irmin in the process there is nothing to protect.
- No GraphQL layer between the server and its store. Two plans in this repository exist because of that layer: `PLAN-IRMIN-GRAPHQL-VARIABLES.md` and `PLAN-IRMIN-RECURSIVE-READ.md`. Register also carries a local patch to `irmin-graphql` in its Irmin image.
- A later path to Unikraft and to a unikernel, in the order of §3.

What it costs is §4.

## 3. Ruled

**The order, ruled 2026-11-13.** First a multicore Linux binary. Later Unikraft. A unikernel only once it supports several cores. The reason is the Monte Carlo simulation: it runs trials in parallel today, and a MirageOS unikernel on Solo5 runs on one domain.

**Irmin is linked into the server process, ruled 2026-11-14.**

**Where it runs.** The Linux binary replaces the Scala image in the same pod. If `PLAN-KATA-RUNTIME.md` in `register-infra` has landed, that pod runs under Kata Containers, and the migration changes nothing about that.

## 4. What is being ported, and the four costly areas

The sizes are from the feasibility document, counted in lines of main source: the shared `common` module 8 545, the server 13 387, the Laminar frontend 12 221, the vql-engine library 6 877, the metalog-distribution library 1 332, and hdr-rng 113.

Four areas carry most of the cost and risk. The first three are the feasibility document's. The fourth replaces its fourth, which was the one-core limit of a unikernel and does not apply to a Linux binary.

1. **The frontend.** It is 12 221 lines of Scala.js and shares its endpoint definitions and its validation with the server through the cross-compiled `common` module. OEM-D-2.
2. **A replacement for Tapir.** Tapir gives one definition from which the server routes, the browser client and the API documentation are all derived. OCaml has no equivalent in use here. OEM-D-3.
3. **The quadratic-programming solver in the metalog library.** The metalog fit uses the Java library ojAlgo. An OCaml port needs a small replacement.
4. **Irmin's programming interface.** Irmin 3.11, the version register pins, is written against Lwt, the older promise library. Its conversion to Eio was merged into a separate `eio` branch in July 2025 and has not reached a release. OEM-D-1.

## 5. Open decisions

None of these is ruled, and none is put to the user until the migration is taken up. They are recorded so that the direction is not re-derived.

**OEM-D-1 — how an Eio program uses an Irmin written against Lwt.**
- A) Run Irmin 3.11 through a bridge that lets Lwt code run under an Eio program. Unverified: that such a bridge runs Irmin's pack backend correctly across several domains. The check is one program that writes and reads a store from two domains.
- B) Build against Irmin's `eio` branch. Unverified: its state, and whether it reads a store the released version wrote.
- C) Wait for a release of Irmin on Eio.

**OEM-D-2 — what happens to the frontend.**
- A) It stays Scala.js. The `common` module is then kept for the browser alone, and the two sides no longer share endpoint definitions. A contract test or a generated specification has to hold them together.
- B) It is ported as well, to an OCaml browser library.

**OEM-D-3 — how the API is described once Tapir is gone.**
The feasibility document lists three: an existing OCaml endpoint library, an in-house one of about a thousand lines, or a hand-kept OpenAPI specification with a contract test. Its recommendation was the in-house library. That recommendation was made for a unikernel and is not carried over as this plan's.

**OEM-D-4 — which Irmin backend the process uses, and where its files are.**
The Irmin image uses the pack backend on a directory. In the process it would use the same backend on a volume mounted into the server's pod. The pod then holds state, which it does not today. Whether the existing store's files are read as they are is unverified.

**OEM-D-5 — whether the history and merge features keep their behaviour.**
Scenario branches, history and merge are built on Irmin's GraphQL operations and on register's local patch to them. Calling the library directly replaces every one of those calls. What the patch changes, and whether the library needs the same change, is unverified.

## 6. What to measure before taking it up

The first is the user's own doubt, turned into a check.

1. Simulation time for the largest trees, on the current build, at the trial parallelism in use and at 1. This says how much parallelism is worth, which is the reason the Linux binary comes first.
2. A two-domain OCaml program that fits one metalog and draws ten thousand losses, timed against the current implementation.
3. The check of OEM-D-1 option A.

## 7. Standing instruction this plan touches

`docs/WORKING-INSTRUCTIONS.md` in `register-infra` says "Minimize OCaml code — prefer using Irmin as an external service" and "Notify user before generating OCaml". This plan generates none. Taking the migration up reverses the first sentence, and that is the user's to state at that time.

## 8. Effect on `register-infra`, named once

If the migration lands: the `infra/helm/irmin/` chart and its Application are removed, the store's volume attaches to the register pod, the two NetworkPolicies between register and Irmin are removed, and row 3 of the connection table in `PLAN-TLS-TRANSPORT-SECURITY.md` no longer exists.

## 9. Terminology impact

None while the plan is unscheduled. If it lands, `docs/user/TERMINOLOGY.md` section 8.1 names "the store" as one of three peers this service calls. The store would then be inside the service, and that entry becomes stale.
