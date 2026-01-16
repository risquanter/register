# Persistence Architecture Requirements

**Referenced by:** ADR-004a, ADR-004b  
**Date:** 2026-01-16

---

## Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Tree Structure** | N-ary tree (`RiskPortfolio` has `children: List[RiskNode]`) |
| | Nodes have unique IDs |
| | Each node caches precomputed aggregate (LEC data) |
| **Efficient Updates** | O(1) node lookup by ID |
| | O(depth) cache invalidation/recomputation on change |
| | Structural sharing for unchanged subtrees |
| **Versioning & Scenarios** | Full history of every change |
| | Named branches/tags for scenarios ("high-probability", "post-mitigation") |
| | Diff between any two versions |
| | Recover exact state at any point |
| **Real-Time Collaboration** | Multiple users editing different parts simultaneously |
| | Automatic merge for disjoint edits |
| | Conflict detection for same-node edits |
| | Push notifications to all subscribers |
| **Streaming API** | Never transmit entire tree at once |
| | Subscribe to subtree changes |
| | Depth-controlled traversal |

---

## Non-Functional Requirements

- ADR-001: Iron types at boundaries (validation)
- ADR-002: ZIO logging + OpenTelemetry tracing
- ADR-003: HDR provenance for reproducible simulation

---

## Source

Requirements consolidated from:
- Tree Zippers Conversation (Aug-Oct 2025)
- Split-pane UI design discussion (Jan 2026)
- Event-sourcing architecture analysis (Jan 2026)
