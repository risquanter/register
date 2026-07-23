# Risquanter Register — API Tutorial

This guide walks through the HTTP API step by step: parameterising leaf risks, bootstrapping a workspace, fetching simulation results, and running vague quantifier queries.

## Prerequisites & Assumptions

| Requirement | Detail |
|---|---|
| Running Register instance | The examples target the **docker-compose stack** started with `docker compose --profile frontend up -d` — the nginx frontend on `localhost:18080` proxies all API paths through to the backend. In-memory mode is the default; no persistence backend is required. See [Getting Started (in-memory storage)](../README.md#getting-started-in-memory-storage) in the README. |
| API endpoint | `http://localhost:18080` throughout (the frontend container). If you run the bare backend instead (`docker compose up -d register-server`, or Vite dev mode), substitute `localhost:8090`. |
| HTTP client | Examples use [httpie](https://httpie.io/) (`http` command) and [curl](https://curl.se/). Install either one — both produce the same results. |
| `jq` | Used in curl examples for pretty-printing JSON responses. Install via your OS package manager (`brew install jq`, `apt install jq`, etc.). |
| `workspaceKey` | The 128-bit capability token returned by the bootstrap step. It is embedded in every subsequent URL. **Treat it like a shared secret** — anyone who holds it can read and modify that workspace. |
| Ready-to-run scripts | The [`examples/`](../examples/) directory contains fully self-contained scripts for all scenarios shown here. `chmod +x examples/*.sh` and run directly. |

---

## Leaf Risk Parameters: A Modeller's Guide

Every leaf node requires three things: a name, an occurrence probability, and a loss characterisation. The occurrence probability is the top-level `probability` field; the loss characterisation is a nested `distributionShape` object carrying `distributionType` and its parameters (`minLoss`/`maxLoss` for lognormal, `percentiles`/`quantiles`/`terms` for expert — unused fields sent as `null`). The loss characterisation comes in two forms — pick whichever matches how your expert articulates their knowledge.

### Confidence interval mode (`distributionType: "lognormal"`)

Use this when your expert can bound the loss outcome with a confidence statement. The two fields are:

| Field | What it means |
|---|---|
| `probability` | The chance this risk event occurs in the modelling period. `0.20` means a 1-in-5 chance per year. |
| `minLoss` | The lower end of a **90 % confidence interval** on loss size — "when this event occurs, I'd be surprised if losses were below this." |
| `maxLoss` | The upper end of that same interval — "when this event occurs, I'd be surprised if losses exceeded this." |

Concretely: a cyber breach team says *"we think there's a 20 % chance of a breach this year; if one happens, we're 90 % confident the financial damage falls somewhere between $500 K and $8 M."*

```json
{
  "name": "Cyber Breach",
  "probability": 0.2,
  "distributionShape": {
    "distributionType": "lognormal",
    "minLoss": 500000,
    "maxLoss": 8000000,
    "percentiles": null,
    "quantiles": null,
    "terms": null
  }
}
```

The system constructs a right-skewed loss distribution from those two bounds. Nothing else is needed. The simulation will reflect not just the expected loss but the full spread — including the tail scenarios that matter for capital planning.

### Quantile mode (`distributionType: "expert"`)

Use this when your expert can speak to several points on the loss landscape, not just the credible range. The two fields are:

| Field | What it means |
|---|---|
| `probability` | The chance this risk event occurs, same as above. |
| `percentiles` | The probability levels your expert is making statements at, as decimal fractions. `[0.25, 0.50, 0.75, 0.95]` means the 25th, 50th, 75th, and 95th percentiles of the conditional loss distribution. |
| `quantiles` | The loss amounts at each of those percentiles, in the same order. |

Concretely: a supply chain analyst says *"there's a 10 % chance of a major disruption this year; if it happens, only one time in twenty **(P95)** would losses exceed $15 M, three-quarters of the time **(P75)** they stay below $4 M, about half the time **(P50)** below $1 M, and only in one case out of four **(P25)** are losses as low as $200 K."*

```json
{
  "name": "Ransomware",
  "probability": 0.1,
  "distributionShape": {
    "distributionType": "expert",
    "minLoss": null,
    "maxLoss": null,
    "percentiles": [
      0.25,
      0.5,
      0.75,
      0.95
    ],
    "quantiles": [
      200000,
      1000000,
      4000000,
      15000000
    ],
    "terms": null
  }
}
```

The system fits a flexible distribution that passes exactly through every stated point, honouring the expert's full picture of the loss landscape without imposing any parametric assumption.

**Minimum requirement:** two percentile-quantile pairs. A view on the median and the 95th percentile is already enough. Add more pairs wherever the expert has a well-formed opinion — each additional point sharpens the fitted shape.

---

## 1. Bootstrap a Workspace & First Tree

Create a workspace with a two-level risk tree: one portfolio ("Operations") containing four leaf risks — a cyber breach (lognormal), ransomware (expert quantiles), a supply chain disruption (lognormal), and a regulatory fine (lognormal):

```bash
http POST localhost:18080/workspaces \
  name="Operational Risk Model" \
  portfolios:='[
    {
      "name": "Operations",
      "parentName": null
    },
    {
      "name": "IT Risk",
      "parentName": "Operations"
    },
    {
      "name": "Third Party Risk",
      "parentName": "Operations"
    }
  ]' \
  leaves:='[
    {
      "name": "Cyber Breach",
      "parentName": "IT Risk",
      "probability": 0.2,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 500000,
        "maxLoss": 8000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Ransomware",
      "parentName": "IT Risk",
      "probability": 0.1,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          200000,
          1000000,
          4000000,
          15000000
        ],
        "terms": null
      }
    },
    {
      "name": "Supply Chain Disruption",
      "parentName": "Third Party Risk",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 300000,
        "maxLoss": 3000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Regulatory Fine",
      "parentName": "Third Party Risk",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 100000,
        "maxLoss": 2000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    }
  ]'
```

The response contains your workspace key and the auto-assigned tree ID:

```json
{
  "workspaceKey": "aB3x7kLm2Pq9RwZvNsYt8u",
  "tree": { "id": "01J...", "name": "Operational Risk Model", ... },
  "expiresAt": "2026-04-22T10:00:00Z"
}
```

Store both values — every subsequent request uses them:

```bash
export WS_KEY=aB3x7kLm2Pq9RwZvNsYt8u
export TREE_ID=01J...
```

## 2. Fetch Tree Summary

**httpie:**
```bash
http GET "localhost:18080/w/$WS_KEY/risk-trees/$TREE_ID"
```

**curl:**
```bash
curl -s "http://localhost:18080/w/$WS_KEY/risk-trees/$TREE_ID" | jq .
```

Returns the simulation summary for every node in the tree, including P95/P99 quantile statistics and LEC curve points.

## 3. Run a Vague Quantifier Query

Queries are submitted via HTTP POST to the tree's query endpoint. The response reports whether the quantifier is satisfied, the exact proportion, and the IDs of matching nodes (useful for tree highlighting in the frontend):

```json
{
  "satisfied": true,
  "proportion": 0.667,
  "satisfyingNodeIds": ["<Cyber Breach node id>", "<Ransomware node id>"],
  "rangeSize": 3,
  "satisfyingCount": 2,
  "sampleSize": 3,
  "queryEcho": "Q[>=]^{1/3} x (leaf(x), gt_loss(p99(x), 5000000))"
}
```

### Query Language Reference

| Syntax element | Meaning |
|---|---|
| `Q[>=]^{2/3} x (range(x), pred(x))` | True when at least 2/3 of elements in `range` satisfy `pred` |
| `Q[<=]^{1/2} x (range(x), pred(x))` | True when at most 1/2 of elements in `range` satisfy `pred` |
| `Q[~]^{1/2} x (range(x), pred(x))` | True when approximately 1/2 of elements satisfy `pred` (fuzzy) |
| `leaf(x)` | x is a leaf risk node |
| `portfolio(x)` | x is a portfolio node |
| `child_of(x, "Parent Name")` | x is a direct child of the named node |
| `descendant_of(x, "Name")` | x is any descendant of the named node |
| `leaf_descendant_of(x, "Name")` | x is a leaf anywhere under the named node |
| `p95(x)`, `p99(x)` | P95 / P99 loss value for node x (returns Loss) |
| `lec(x, 1000000)` | Exceedance probability at \$1 M for node x (returns Probability) |
| `gt_loss(p95(x), 5000000)` | P95 loss exceeds \$5 M (Loss comparison) |
| `gt_prob(lec(x, 1000000), 0.05)` | Exceedance probability at \$1 M exceeds 5 % (Probability comparison) |

---

## Enterprise Risk Model Example

The following example builds a realistic 4-domain financial services risk tree (32 nodes: 1 root + 10 portfolios + 21 leaves) to demonstrate queries at enterprise complexity.

```
Enterprise Risk  (root)
├── Operational Risk
│   ├── Technology and Cyber
│   │   ├── Ransomware Attack             expert  15%  P25=$500K P50=$2M P75=$8M  P95=$25M
│   │   ├── Cloud Provider Outage         lognorm 30%  $200K–$4M
│   │   ├── Data Breach - PII               lognorm 10%  $1M–$15M
│   │   └── Insider Threat                lognorm  5%  $2M–$20M
│   ├── Process and People
│   │   ├── Key Person Departure          lognorm 20%  $100K–$800K
│   │   ├── Internal Fraud                expert   8%  P25=$200K P50=$1M P75=$4M  P95=$18M
│   │   └── Process Failure               lognorm 25%  $50K–$500K
│   └── Third-Party and Supply Chain
│       ├── Critical Vendor Failure       lognorm 12%  $500K–$5M
│       ├── Outsourcing SLA Breach        lognorm 20%  $100K–$1.5M
│       └── Concentration Risk            expert   8%  P25=$1M   P50=$4M          P95=$18M
├── Financial Risk
│   ├── Market Risk
│   │   ├── Equity Portfolio Drawdown     expert  35%  P25=$1M P50=$4M P75=$12M  P95=$28M
│   │   └── FX Adverse Move               lognorm 40%  $500K–$8M
│   ├── Credit Risk
│   │   ├── Counterparty Default          lognorm  5%  $3M–$30M
│   │   └── Credit Downgrade Wave         expert  15%  P25=$800K P50=$3M          P95=$20M
│   └── Liquidity Risk
│       └── Funding Squeeze               lognorm  8%  $2M–$25M
├── Compliance and Legal Risk
│   ├── Regulatory Action                 lognorm 12%  $2M–$50M
│   ├── Litigation                        expert   8%  P25=$300K P50=$2M P75=$8M  P95=$40M
│   └── GDPR / Data Protection Fine       lognorm 15%  $500K–$10M
└── Strategic and Reputational Risk
    ├── ESG Controversy                   lognorm 10%  $1M–$12M
    ├── M and A Integration Failure      lognorm  5%  $5M–$40M
    └── Product Recall / Liability        expert   6%  P25=$1M   P50=$5M          P95=$35M
```

### Bootstrap (httpie)

```bash
http POST localhost:18080/workspaces \
  name="Financial Services Enterprise Risk" \
  portfolios:='[
    {
      "name": "Enterprise Risk",
      "parentName": null
    },
    {
      "name": "Operational Risk",
      "parentName": "Enterprise Risk"
    },
    {
      "name": "Technology and Cyber",
      "parentName": "Operational Risk"
    },
    {
      "name": "Process and People",
      "parentName": "Operational Risk"
    },
    {
      "name": "Third-Party and Supply Chain",
      "parentName": "Operational Risk"
    },
    {
      "name": "Financial Risk",
      "parentName": "Enterprise Risk"
    },
    {
      "name": "Market Risk",
      "parentName": "Financial Risk"
    },
    {
      "name": "Credit Risk",
      "parentName": "Financial Risk"
    },
    {
      "name": "Liquidity Risk",
      "parentName": "Financial Risk"
    },
    {
      "name": "Compliance and Legal Risk",
      "parentName": "Enterprise Risk"
    },
    {
      "name": "Strategic and Reputational Risk",
      "parentName": "Enterprise Risk"
    }
  ]' \
  leaves:='[
    {
      "name": "Ransomware Attack",
      "parentName": "Technology and Cyber",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          500000,
          2000000,
          8000000,
          25000000
        ],
        "terms": null
      }
    },
    {
      "name": "Cloud Provider Outage",
      "parentName": "Technology and Cyber",
      "probability": 0.3,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 200000,
        "maxLoss": 4000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Data Breach - PII",
      "parentName": "Technology and Cyber",
      "probability": 0.1,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 1000000,
        "maxLoss": 15000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Insider Threat",
      "parentName": "Technology and Cyber",
      "probability": 0.05,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 2000000,
        "maxLoss": 20000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Key Person Departure",
      "parentName": "Process and People",
      "probability": 0.2,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 100000,
        "maxLoss": 800000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Internal Fraud",
      "parentName": "Process and People",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          200000,
          1000000,
          4000000,
          18000000
        ],
        "terms": null
      }
    },
    {
      "name": "Process Failure",
      "parentName": "Process and People",
      "probability": 0.25,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 50000,
        "maxLoss": 500000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Critical Vendor Failure",
      "parentName": "Third-Party and Supply Chain",
      "probability": 0.12,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 500000,
        "maxLoss": 5000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Outsourcing SLA Breach",
      "parentName": "Third-Party and Supply Chain",
      "probability": 0.2,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 100000,
        "maxLoss": 1500000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Concentration Risk",
      "parentName": "Third-Party and Supply Chain",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.95
        ],
        "quantiles": [
          1000000,
          4000000,
          18000000
        ],
        "terms": null
      }
    },
    {
      "name": "Equity Portfolio Drawdown",
      "parentName": "Market Risk",
      "probability": 0.35,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          1000000,
          4000000,
          12000000,
          28000000
        ],
        "terms": null
      }
    },
    {
      "name": "FX Adverse Move",
      "parentName": "Market Risk",
      "probability": 0.4,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 500000,
        "maxLoss": 8000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Counterparty Default",
      "parentName": "Credit Risk",
      "probability": 0.05,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 3000000,
        "maxLoss": 30000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Credit Downgrade Wave",
      "parentName": "Credit Risk",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.95
        ],
        "quantiles": [
          800000,
          3000000,
          20000000
        ],
        "terms": null
      }
    },
    {
      "name": "Funding Squeeze",
      "parentName": "Liquidity Risk",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 2000000,
        "maxLoss": 25000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Regulatory Action",
      "parentName": "Compliance and Legal Risk",
      "probability": 0.12,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 2000000,
        "maxLoss": 50000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Litigation",
      "parentName": "Compliance and Legal Risk",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          300000,
          2000000,
          8000000,
          40000000
        ],
        "terms": null
      }
    },
    {
      "name": "GDPR / Data Protection Fine",
      "parentName": "Compliance and Legal Risk",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 500000,
        "maxLoss": 10000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "ESG Controversy",
      "parentName": "Strategic and Reputational Risk",
      "probability": 0.1,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 1000000,
        "maxLoss": 12000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "M and A Integration Failure",
      "parentName": "Strategic and Reputational Risk",
      "probability": 0.05,
      "distributionShape": {
        "distributionType": "lognormal",
        "minLoss": 5000000,
        "maxLoss": 40000000,
        "percentiles": null,
        "quantiles": null,
        "terms": null
      }
    },
    {
      "name": "Product Recall / Liability",
      "parentName": "Strategic and Reputational Risk",
      "probability": 0.06,
      "distributionShape": {
        "distributionType": "expert",
        "minLoss": null,
        "maxLoss": null,
        "percentiles": [
          0.25,
          0.5,
          0.95
        ],
        "quantiles": [
          1000000,
          5000000,
          35000000
        ],
        "terms": null
      }
    }
  ]'
```

### Bootstrap (curl)

```bash
curl -s -X POST http://localhost:18080/workspaces \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Financial Services Enterprise Risk",
    "portfolios": [
      {
        "name": "Enterprise Risk",
        "parentName": null
      },
      {
        "name": "Operational Risk",
        "parentName": "Enterprise Risk"
      },
      {
        "name": "Technology and Cyber",
        "parentName": "Operational Risk"
      },
      {
        "name": "Process and People",
        "parentName": "Operational Risk"
      },
      {
        "name": "Third-Party and Supply Chain",
        "parentName": "Operational Risk"
      },
      {
        "name": "Financial Risk",
        "parentName": "Enterprise Risk"
      },
      {
        "name": "Market Risk",
        "parentName": "Financial Risk"
      },
      {
        "name": "Credit Risk",
        "parentName": "Financial Risk"
      },
      {
        "name": "Liquidity Risk",
        "parentName": "Financial Risk"
      },
      {
        "name": "Compliance and Legal Risk",
        "parentName": "Enterprise Risk"
      },
      {
        "name": "Strategic and Reputational Risk",
        "parentName": "Enterprise Risk"
      }
    ],
    "leaves": [
      {
        "name": "Ransomware Attack",
        "parentName": "Technology and Cyber",
        "probability": 0.15,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            500000,
            2000000,
            8000000,
            25000000
          ],
          "terms": null
        }
      },
      {
        "name": "Cloud Provider Outage",
        "parentName": "Technology and Cyber",
        "probability": 0.3,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 200000,
          "maxLoss": 4000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Data Breach - PII",
        "parentName": "Technology and Cyber",
        "probability": 0.1,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 1000000,
          "maxLoss": 15000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Insider Threat",
        "parentName": "Technology and Cyber",
        "probability": 0.05,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 2000000,
          "maxLoss": 20000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Key Person Departure",
        "parentName": "Process and People",
        "probability": 0.2,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 100000,
          "maxLoss": 800000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Internal Fraud",
        "parentName": "Process and People",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            200000,
            1000000,
            4000000,
            18000000
          ],
          "terms": null
        }
      },
      {
        "name": "Process Failure",
        "parentName": "Process and People",
        "probability": 0.25,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 50000,
          "maxLoss": 500000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Critical Vendor Failure",
        "parentName": "Third-Party and Supply Chain",
        "probability": 0.12,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 500000,
          "maxLoss": 5000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Outsourcing SLA Breach",
        "parentName": "Third-Party and Supply Chain",
        "probability": 0.2,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 100000,
          "maxLoss": 1500000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Concentration Risk",
        "parentName": "Third-Party and Supply Chain",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.95
          ],
          "quantiles": [
            1000000,
            4000000,
            18000000
          ],
          "terms": null
        }
      },
      {
        "name": "Equity Portfolio Drawdown",
        "parentName": "Market Risk",
        "probability": 0.35,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            1000000,
            4000000,
            12000000,
            28000000
          ],
          "terms": null
        }
      },
      {
        "name": "FX Adverse Move",
        "parentName": "Market Risk",
        "probability": 0.4,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 500000,
          "maxLoss": 8000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Counterparty Default",
        "parentName": "Credit Risk",
        "probability": 0.05,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 3000000,
          "maxLoss": 30000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Credit Downgrade Wave",
        "parentName": "Credit Risk",
        "probability": 0.15,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.95
          ],
          "quantiles": [
            800000,
            3000000,
            20000000
          ],
          "terms": null
        }
      },
      {
        "name": "Funding Squeeze",
        "parentName": "Liquidity Risk",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 2000000,
          "maxLoss": 25000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Regulatory Action",
        "parentName": "Compliance and Legal Risk",
        "probability": 0.12,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 2000000,
          "maxLoss": 50000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Litigation",
        "parentName": "Compliance and Legal Risk",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            300000,
            2000000,
            8000000,
            40000000
          ],
          "terms": null
        }
      },
      {
        "name": "GDPR / Data Protection Fine",
        "parentName": "Compliance and Legal Risk",
        "probability": 0.15,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 500000,
          "maxLoss": 10000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "ESG Controversy",
        "parentName": "Strategic and Reputational Risk",
        "probability": 0.1,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 1000000,
          "maxLoss": 12000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "M and A Integration Failure",
        "parentName": "Strategic and Reputational Risk",
        "probability": 0.05,
        "distributionShape": {
          "distributionType": "lognormal",
          "minLoss": 5000000,
          "maxLoss": 40000000,
          "percentiles": null,
          "quantiles": null,
          "terms": null
        }
      },
      {
        "name": "Product Recall / Liability",
        "parentName": "Strategic and Reputational Risk",
        "probability": 0.06,
        "distributionShape": {
          "distributionType": "expert",
          "minLoss": null,
          "maxLoss": null,
          "percentiles": [
            0.25,
            0.5,
            0.95
          ],
          "quantiles": [
            1000000,
            5000000,
            35000000
          ],
          "terms": null
        }
      }
    ]
  }' | jq '{workspaceKey: .workspaceKey, treeId: .tree.id, expiresAt: .expiresAt}'
```
