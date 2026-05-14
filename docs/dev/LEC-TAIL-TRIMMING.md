# LEC Chart Tail Trimming

## Overview

Loss Exceedance Curves (LECs) display the probability that a loss will exceed a
given threshold: $P(\text{Loss} \geq x)$. For fat-tailed distributions—common in
cyber, operational, and catastrophe risk—the raw curve extends far to the right
with exceedance probabilities indistinguishable from zero. This long, flat tail
can consume 50–90% of the chart's horizontal space while conveying no actionable
information.

The system trims this visual tail at a **0.5% exceedance threshold** (1-in-200
probability). This document explains why that is safe and appropriate.

---

## 1. Visualisation Only — All Calculations Use the Full Dataset

The trimming applies **exclusively to the rendered chart data** returned by
`LECGenerator.generateCurvePointsMulti`. It controls which tick marks appear on
the x-axis of the Vega-Lite chart.

It does **not** affect:

| Capability | Unaffected? | Why |
|---|---|---|
| `RiskResult` (cached simulation outcomes) | ✅ | The full Monte Carlo outcome set is preserved in memory. Nothing is deleted. |
| `probOfExceedance(threshold)` | ✅ | Any threshold can be queried at any time via the API, regardless of what the chart displays. |
| Quantile calculations (VaR, p95, p99) | ✅ | Computed directly from the outcome distribution, not from chart points. |
| Risk aggregation (`Identity[RiskResult].combine`) | ✅ | Operates on raw trial-wise losses, not on rendered curves. |
| Provenance and reproducibility | ✅ | Trial-level data and PRNG seeds remain intact. |

In summary: the chart is a **window onto the data**, not the data itself.
Narrowing the window does not discard what lies outside it.

---

## 2. The Cutoff Aligns With Industry Standards

The 0.5% exceedance threshold corresponds to a **1-in-200 year return period**.
This is not an arbitrary choice—it is the most widely adopted regulatory floor
for loss exceedance reporting in quantitative risk management.

### Regulatory and industry precedent

| Framework | Domain | Reporting floor | Return period | Exceedance probability |
|---|---|---|---|---|
| **Solvency II** (EU Directive 2009/138/EC) | Insurance & reinsurance | 99.5th percentile | 200 years | **0.5%** |
| **Basel III / IV** | Banking (market risk) | 99th percentile | 100 years | 1.0% |
| **TCFD / ISSB** | Climate-related financial risk | 95th percentile | 20 years | 5.0% |
| **ILS / Cat modelling** | Catastrophe bonds | Varies (typically 1-in-250) | 250 years | 0.4% |

The chosen threshold of 0.5% matches the **most conservative standard in general
use** (Solvency II). Events beyond this floor—those with less than a 1-in-200
chance of being exceeded—are:

- **Statistically unreliable** at typical simulation sizes (see below).
- **Not reported** in standard regulatory filings.
- **Available on demand** via the exceedance probability API for users who need
  them for bespoke analysis.

### Statistical reliability at the boundary

With $N = 10{,}000$ Monte Carlo trials, the standard error of the estimated
exceedance probability $\hat{S}(x)$ is:

$$\text{SE} = \sqrt{\frac{\hat{S}(x)\,(1 - \hat{S}(x))}{N}}$$

| $\hat{S}(x)$ | Trials exceeding $x$ | Standard error | Relative error |
|---|---|---|---|
| 5.0% | ~500 | ±0.22% | 4% |
| 1.0% | ~100 | ±0.10% | 10% |
| **0.5%** | **~50** | **±0.07%** | **14%** |
| 0.1% | ~10 | ±0.03% | 32% |
| 0.01% | ~1 | ±0.01% | 100% |

Below 0.5%, the relative uncertainty grows rapidly. Displaying these values on a
chart implies a precision the simulation data does not support. The API still
returns them (with the implicit caveat that they are point estimates from a
finite sample), but the chart avoids presenting them as reliable visual facts.

---

## 3. Implementation Details

- **Shared domain trimming**: when multiple curves are displayed together, the
  trim point is the rightmost tick where *any* curve still exceeds the threshold.
  This ensures all curves share the same x-axis for valid visual comparison,
  and no curve's meaningful region is cut short by another curve's earlier decay.

- **+1 tick extension**: the chart includes one additional tick beyond the last
  meaningful point, so the rendered line visually descends toward zero rather
  than terminating abruptly at a non-zero value.

- **Configurable**: the threshold is defined as `LECGenerator.tailCutoff` and
  can be adjusted if requirements change (e.g., a future regulatory context
  demands 1-in-250 or 1-in-100 floors).

---

## References

- EU Directive 2009/138/EC (Solvency II), Article 101(3): *"The Solvency
  Capital Requirement shall correspond to the Value-at-Risk of the basic own
  funds [...] subject to a confidence level of 99.5% over a one-year period."*
- Basel Committee on Banking Supervision, *Minimum capital requirements for
  market risk* (BCBS 457), January 2019.
- TCFD, *Recommendations of the Task Force on Climate-related Financial
  Disclosures*, June 2017.
