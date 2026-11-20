# Threat Modelling — Methodology and Concepts

This document says how register's threat models are built and what their terms
and drawings mean. It is read before a model, and it is the reference when a
model is written or reviewed. The models apply it. They do not repeat it.

---

## 1. The documents

A threat model is written for one system or one deployment of it. Each model has
up to two files:

| File | Holds |
|---|---|
| `<NAME>-TM.md` | the model: schema, inventory, boundaries, data flows, diagram and threat entries |
| `<NAME>-TM-NOTE.md` | assumptions and observations that concern one component of that model |

There are five models. One covers what runs in every deployment, and four cover
the deployments:

| Model | Deployment id | Auth mode | Repository |
|---|---|---|---|
| `APPLICATION-TM.md` | — | — | register |
| compose, in-memory | `compose-inmem` | `capability-only` | register |
| compose, persistent | `compose-persistent` | `capability-only` | register |
| k3d, Layer 1 | `k3d-L1` | `identity` | register-infra |
| k3d, Layer 2 | `k3d-L2` | `fine-grained` | register-infra |

Each model carries two lines directly above its diagram:

```markdown
**System version:** 0.25.2
**Assessed:** version 0.25.0 · commit b49e50fd · stage 5
```

The first line is the version the repository is at. The version bump command in
the register-dev skill rewrites it in every `docs/dev/threat-models/*-TM.md`
together with `build.sbt`. The second line says what the model describes: the
version and the commit that were assessed, and the last stage of the
threat-modelling workflow that passed against them. Only a stage that passes
rewrites it. The commit is the one the assessment read, which is the parent of
the commit that lands the model, because a commit cannot name its own hash.

When the two versions differ, the system has moved since the model was
assessed, and the model is due for re-checking.

The application model holds every threat that does not depend on the deployment.
A deployment model lists only the elements, boundaries and flows its deployment
adds. It refers to a shared threat by its id and gives that threat's status in
the deployment. The same threat can be open in compose and closed at Layer 2, so
a threat's status is recorded once per deployment.

## 2. Format and tools

**The models are Markdown with one YAML block per threat, and the diagrams are
Mermaid.** No threat-modelling tool is used. Three tools were considered:

- **Threagile** reads a YAML model and generates risks and diagrams from built-in
  rules.
- **OWASP pytm** describes the system as Python classes and generates diagrams
  and threats from a threat library.
- **OWASP Threat Dragon** and **AWS Threat Composer** are graphical editors that
  save JSON.

The generating tools match generic web and cloud rules. register's threats come
from its own decisions: capability URLs (ADR-021), validation at the process edge
(ADR-039), the externalized enforcement pattern (ADR-024) and SpiceDB
relationships. No generic rule knows these, so their threats would be written by
hand in any of the tools. Each tool would also be a new dependency. ADR-020 §11
requires that "A dependency from an individual account or an unestablished
organisation requires **case-by-case user approval before it is added**", and
every pinned tool waits out a cooldown. The graphical editors save JSON that is
hard to review as a diff.

Markdown and YAML diff and review like any other document, and need nothing new
installed.

**Threat statements use the Threat Composer grammar** without the tool: "A
\<threat source\> with \<prerequisites\> can \<threat action\>, which leads to
\<threat impact\>, negatively impacting \<impacted assets\>."

**Mechanical checks are planned with conftest.** A Rego policy can check that
each threat marked mitigated names a test, that each cited path exists, and that
each `applies_to` or `status` key is a declared deployment id. conftest is pinned
in register-infra's `mise.toml`. register has no `mise.toml`. Running conftest in
register's suite is therefore a new dependency for register, decided under the
`supply-chain` skill.

## 3. A model describes the system as built

A model describes one version of the system, and its `**Assessed:**` line names
that version and commit (§1). A boundary is drawn only where its control exists in that
version. A control that is intended but not built appears as a mitigation with
status `open` and a `todo`. It never appears as a boundary.

The reason is mechanical. STRIDE is run on each flow that crosses a boundary. A
boundary drawn where no control exists makes each flow across it look checked,
and the threat that the control is missing disappears from the analysis. When
the control lands, the threat's status changes and the boundary is added.

A model may describe a design that is not built yet. Shostack's first question
of threat modelling is "what are we working on?", and the answer can be a plan.
Such a model says at its top that it describes a design. register's compose
deployments and its k3d cluster run today, so their models describe the current
state.

## 4. Boundary means trust boundary

In these models, **boundary** always means trust boundary, in the component
sense defined below.

The project uses "trust boundary" in a second context. In the codebase, the
decision records and [TERMINOLOGY.md](../../user/TERMINOLOGY.md) §8.1, a trust
boundary is "the line a value may not cross without being checked". That
meaning is about values: where a value must be checked, and whose fault a failed
check is. It also covers boundaries no component drawing shows, such as the
narrow set of places the workspace key may appear. The threat models' meaning is
about components: who can reach a component, and which control stops everyone
else. The two give the same line at a process edge, where ADR-039's check on
every incoming value is the control of the process boundary. Elsewhere they
differ, and a threat model always means the component sense.

**Definition.** A boundary encloses one or more components, and a control
separates the principals inside it from those outside. A principal is an actor
with its own identity and rights: a user, a process, a service account. Shostack:
"A trust boundary indicates separation of principals by some control, and the
boundary is instantiated by the control." His book puts it practically: draw
trust boundaries "wherever different people control different things", and
"almost anywhere you can argue for different privileges". Microsoft Learn puts
them "where privilege levels change".

**What the definition implies:**

- **No control, no boundary.** The question that decides a boundary is "which
  control enforces it?". Each boundary in a model names its control.
- **Different privilege, not necessarily higher.** A principal outside a
  boundary needs a privilege it does not hold by default to reach a component
  inside. Two peers can be separated with neither ranked above the other.
  register-server's two stores are such peers: Irmin and PostgreSQL do not call
  each other, hold different data, and have different access rules.
- **Boundaries nest.** Shostack's whitepaper notes that a diagram will usually
  have more than one boundary. The deployment encloses the server process, which
  in a zero-trust design is protected again on its own. Zero trust, as NIST SP
  800-207 is commonly summarised, puts an enforcement point in front of each
  resource and removes the trusted zone inside one large perimeter. This
  document relies on secondary summaries of that publication.
- **The same rights mean one boundary.** If one credential opens two stores,
  whoever holds it or steals it reaches both, and nothing separates them. They
  are inside one boundary.
- **Every process has its own boundary.** Its control is process isolation:
  the process's own memory, and in a container its own filesystem and
  namespaces. That control protects the process's memory and execution. It does
  not decide who can call the process's ports, so who can is recorded in the
  table of §8. A boundary therefore never claims that its process is
  unreachable. It claims only that the process is separate.
- **Separate credentials mean separate boundaries.** If each store checks its
  own credential, two controls exist and two boundaries are drawn. An outer
  boundary around both survives only if a further control still encloses them,
  such as a network policy that admits only register-server.

**Relation to ADR-039 and the code's trust zones.** ADR-039 §1 states that "the
trust region is the memory of one process" and that a store the service wrote
to is outside it. `TrustZone` and `PeerZone` in the code name where a value came
from: the client, the environment, the mesh header, a peer, or the service. That
is a statement about which values the service trusts, and it decides where
validation happens. A process boundary satisfies both views. ADR-039 names two
processes, the server and the SPA. The threat models give every other process
its own boundary as well, by the rule above. The stores are outside the server
process in both views.

## 5. Elements, data flows and boundaries

A model has three kinds of thing, and each is listed separately:

- **Elements** — processes, stores and external actors (APPLICATION-TM §2.1).
- **Boundaries** — regions, each with its control and the deployments where it
  exists (§2.4).
- **Data flows** — numbered `DF<n>`. Each flow is listed once, in the direction
  of the request, and its response travels the same path back. Each row says
  what the flow carries, which boundaries it crosses, the ADR-039 ingress point
  where it enters the server, and the deployments that have it (§2.5).

A boundary is never a label on a flow, and a flow is never a boundary. A flow
crossing several boundaries lists them all in its row.

## 6. Reading reachability from the diagram

Every process sits in its own boundary (§4), so a boundary always lies between
two processes. Whether one can reach the other is read from the diagram
together with the destination boundary's row in the admits table (§8):

| What the model shows | Meaning |
|---|---|
| An arrow between them | a designed flow. The source reaches the destination, by design |
| No arrow, and the destination's boundary admits the source | reachable without a designed flow |
| No arrow, and the destination's boundary does not admit the source | a control stops the source |

"Reachable" here means a principal can open a connection or call the
component. A data flow describes what the system does. A boundary and its row
describe what the system lets happen.

## 7. Lateral movement

The second row of §6 is the lateral-movement case. An attacker who controls one
component can call every component whose boundary admits it, whether or not a
designed flow joins them. In compose, Irmin's boundary admits every element of
the deployment on port 8080, with no credential. A compromised nginx can
therefore read and rewrite every risk tree, although nginx was never designed to
talk to Irmin.

A component's own data flows do not decide who can reach it. Its boundary's
admits row does. Each threat of this kind is a threat entry against the
component that can be reached, with `element` set and `flow: null`.

## 8. When a control admits too much

A drawing would suggest that a boundary's control lets through exactly the drawn
flows. Real controls often admit more: a network that admits everything in
front of a password, a network policy that admits a whole namespace while one
pod uses the port, or one credential shared by two boundaries. The diagram draws
a boundary as one line, and one line cannot say whom its control admits.

Each model therefore has a table, **What each boundary admits** (APPLICATION-TM
§2.6), with one row per boundary and one column per deployment. Each cell
records every principal that can get past the control. The row also lists the
designed crossings and says whether the admitted set is wider.

Worked example: PostgreSQL in compose. The diagram shows boundary B3 around
PostgreSQL, crossed only by DF8 from register-server. The compose file publishes
port 5432 on the host and sets `POSTGRES_PASSWORD: dev-only-password`. Every
element of the deployment, and every process that reaches the host's port 5432,
can open a connection and log in with a password that is public in the
repository. The row records that, and marks it wider than designed. Two threat
entries follow from it: any flaw PostgreSQL has before login is reachable from
all of those principals, and the fixed password turns "blocked by a password"
into "reachable".

A row whose admitted set matches the designed crossings adds nothing the
diagram does not show. Pairs of elements are not tabulated. Each row names every
principal its boundary admits, and that answers the question for every pair.

## 9. Finding threats: STRIDE

STRIDE names six classes of threat: **S**poofing, **T**ampering,
**R**epudiation, **I**nformation disclosure, **D**enial of service and
**E**levation of privilege. Each class is asked of three things:

1. every element in the inventory;
2. every data flow that crosses a boundary;
3. every boundary row in §2.6 marked wider than designed.

Not every class applies to every kind of element. The rule set Microsoft
published for STRIDE per element, which Shostack's *Threat Modeling: Designing
for Security* Appendix B repeats as its Table B-0, is:

| Kind of element | Classes asked |
|---|---|
| External entity | S, R |
| Process | S, T, R, I, D, E |
| Data store | T, I, D, and R when the store holds logs |
| Data flow | T, I, D |

The repudiation entry for a data store has that condition because a log is what
answers a repudiation threat. A store that holds the log can then be tampered
with to remove the evidence.

Each threat found becomes one YAML entry in the model's §4, in the schema of its
§1. Its `statement` uses the grammar of §2. It names the `flow` or the `element`
it acts on, and it maps each mitigation to three references: the decision
record that requires the control, the code or manifest that implements it, and
the test that proves it. Its `status` gives one value per deployment:
`mitigated-tested`, `mitigated-untested`, `accepted`, `open` or
`not-applicable`.

Threat ids are stable and never renamed. T1–T4 keep the ids they have in
[THREAT-CATALOG-(INCOMPLETE).md](../THREAT-CATALOG-(INCOMPLETE).md), and new
threats continue from T5.

## 10. Diagram conventions

| Drawn as | Means |
|---|---|
| Dashed box with its own coloured border and no fill | a boundary. B1 orange `#e8590c`, B2 blue `#1c7ed6`, B3 green `#2f9e44`, B4 purple `#ae3ec9`, B5 amber `#f59f00`, B6 teal `#0c8599`, B7 pink `#d6336c`, B8 grey `#868e96`, B9 brown `#9c6644` |
| Solid box with a light fill, inside a boundary | a component that has ports. Mermaid cannot place a node inside a node, so the component is a nested subgraph |
| Plain box | an element |
| Cylinder | a store |
| Solid arrow | a data flow, compose. The flows out of register-server are drawn solid in every deployment, and the flow table says where each exists |
| Dotted arrow | a data flow, k3d |

Boundaries have no fill so that nested boundaries stay distinct in a dark theme.
The colours are mid-saturation so that they read on a light and on a dark
background. Each boundary is styled with its own `style` line, which every
Mermaid 11 release supports.

## 11. Viewing and checking the diagrams

**In VS Code.** VS Code 1.121 and later draw Mermaid in the Markdown preview
through the built-in extension "Mermaid Markdown Features", published by
`vscode`. VS Code 1.139.1 bundles mermaid 11.17.0. Third-party advisories list
issues in that release that 11.17.1 and 11.17.2 fix, and further issues reach it
through the KaTeX and DOMPurify releases it includes. Each issue needs a hostile
diagram source, and the preview renders only the files you open. No third-party
Mermaid extension is installed. ADR-020 §11 applies to them.

**On GitHub.** GitHub draws Mermaid blocks in Markdown files once pushed.

**Not on mermaid.live.** Pasting a diagram into an online editor sends the
architecture to an outside site.

**Rendering for a check.** A diagram's layout and colours are checked by
rendering it in a light and in a dark theme. This needs only what is already
installed: VS Code's bundled mermaid and Firefox. A small Python script extracts
the Mermaid block. It serves a page holding the block and VS Code's
`mermaid-markdown-features/markdown-preview-out/` files from a local server on
127.0.0.1. The page sets the `--vscode-*` theme variables the preview script
reads. Headless Firefox, with a throwaway profile, takes a screenshot of the
page. The page holds one image that the server answers after a few seconds, so
the page's load event, and with it the screenshot, waits until Mermaid has
drawn. A render checks that the diagram parses, that the nesting comes out as
intended, that labels and arrows stay readable, and that the colours contrast.
It does not check whether the model is right. That is checked against the code,
the manifests and the sources.

## 12. Publication

register is a public repository. Pushing a model publishes it. A threat entry
with status `open` describes a weakness that is not yet fixed.

## 13. Sources

- Adam Shostack, [Why do we call them trust boundaries?](https://shostack.org/blog/trust-and-security-boundaries/)
- Adam Shostack, [Threat Modeling: What, Why, and How?](https://shostack.org/resources/whitepapers/threat-modeling-what-why-how)
- Adam Shostack, *Threat Modeling: Designing for Security*, Wiley, 2014
- Adam Shostack, [Boundaries](https://threatmodelingbook.com/blog/boundaries-threat-model-thursday/)
- Microsoft, [Threat Modeling Tool feature overview](https://learn.microsoft.com/en-us/azure/security/azure-security-threat-modeling-tool-feature-overview)
- Microsoft Learn, [Visualize risk](https://learn.microsoft.com/en-us/training/modules/threat-modeling-enterprise-infrastructure/4-visualize-risk)
- Adam Shostack, [Appendix B, Threat Trees](https://shostack.org/files/tmbookchapters/AppendixB-ThreatTrees-web.pdf), Table B-0
- Larry Osterman, [Threat Modeling Again, STRIDE per Element](https://learn.microsoft.com/en-us/archive/blogs/larryosterman/threat-modeling-again-stride-per-element), Microsoft
- Hernan, Lambert, Ostwald and Shostack, [Uncover Security Design Flaws Using the STRIDE Approach](https://msdn.microsoft.com/en-us/magazine/cc163519.aspx), MSDN Magazine
- W3C, [Threat Modeling Guide](https://www.w3.org/TR/threat-modeling-guide/)
- AWS, [Threat Composer](https://github.com/awslabs/threat-composer)
- [Threagile](https://github.com/Threagile/threagile), [OWASP pytm](https://github.com/OWASP/pytm), [OWASP Threat Dragon](https://owasp.org/www-project-threat-dragon/)
- NIST SP 800-207, *Zero Trust Architecture*, through the summaries by [Palo Alto Networks](https://www.paloaltonetworks.com/cyberpedia/what-is-nist-sp-800-207) and [NetScout](https://www.netscout.com/what-is/nist-sp-800-207)
- [VS Code 1.121 release notes](https://code.visualstudio.com/updates/v1_121)
