# Risk Register API Examples (HTTPie)

## Prerequisites
- Server running on `http://localhost:8080`
- HTTPie installed (`apt install httpie` or `pip install httpie`)

## Growing a Risk Tree - Hierarchical Approach

### 1. Create a simple leaf risk (flat format - backward compatible)
```bash
http POST localhost:8080/api/risktrees \
  name="SimpleRisk" \
  nTrials:=10000 \
  risks:='[
    {
      "name": "MarketRisk",
      "frequency": {"distribution": "Poisson", "lambda": 5.0},
      "severity": {"distribution": "LogNormal", "meanlog": 10.0, "sdlog": 1.5}
    }
  ]'
```

### 2. Create a two-level portfolio (hierarchical format)
```bash
http POST localhost:8080/api/risktrees \
  name="TwoLevelPortfolio" \
  nTrials:=10000 \
  root:='{
    "RiskPortfolio": {
      "name": "OperationalRisks",
      "children": [
        {
          "RiskLeaf": {
            "name": "CyberAttack",
            "frequency": {"distribution": "Poisson", "lambda": 3.0},
            "severity": {"distribution": "LogNormal", "meanlog": 12.0, "sdlog": 2.0}
          }
        },
        {
          "RiskLeaf": {
            "name": "DataBreach",
            "frequency": {"distribution": "Poisson", "lambda": 2.0},
            "severity": {"distribution": "LogNormal", "meanlog": 11.0, "sdlog": 1.8}
          }
        }
      ]
    }
  }'
```

### 3. Create a three-level nested portfolio (complex tree)
```bash
http POST localhost:8080/api/risktrees \
  name="EnterpriseRiskPortfolio" \
  nTrials:=50000 \
  root:='{
    "RiskPortfolio": {
      "name": "EnterpriseRisks",
      "children": [
        {
          "RiskPortfolio": {
            "name": "OperationalRisks",
            "children": [
              {
                "RiskLeaf": {
                  "name": "CyberAttack",
                  "frequency": {"distribution": "Poisson", "lambda": 3.0},
                  "severity": {"distribution": "LogNormal", "meanlog": 12.0, "sdlog": 2.0}
                }
              },
              {
                "RiskLeaf": {
                  "name": "SystemFailure",
                  "frequency": {"distribution": "Poisson", "lambda": 4.5},
                  "severity": {"distribution": "LogNormal", "meanlog": 10.5, "sdlog": 1.5}
                }
              }
            ]
          }
        },
        {
          "RiskPortfolio": {
            "name": "FinancialRisks",
            "children": [
              {
                "RiskLeaf": {
                  "name": "MarketCrash",
                  "frequency": {"distribution": "Poisson", "lambda": 0.5},
                  "severity": {"distribution": "LogNormal", "meanlog": 15.0, "sdlog": 3.0}
                }
              },
              {
                "RiskLeaf": {
                  "name": "CreditDefault",
                  "frequency": {"distribution": "Poisson", "lambda": 1.2},
                  "severity": {"distribution": "LogNormal", "meanlog": 13.5, "sdlog": 2.5}
                }
              }
            ]
          }
        },
        {
          "RiskLeaf": {
            "name": "RegulatoryFine",
            "frequency": {"distribution": "Poisson", "lambda": 1.0},
            "severity": {"distribution": "LogNormal", "meanlog": 14.0, "sdlog": 2.2}
          }
        }
      ]
    }
  }'
```

Response (UUID will be returned):
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000"
}
```

### 4. Retrieve the risk tree
```bash
http GET localhost:8080/api/risktrees/123e4567-e89b-12d3-a456-426614174000
```

Response shows the hierarchical structure:
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "name": "EnterpriseRiskPortfolio",
  "nTrials": 50000,
  "root": {
    "RiskPortfolio": {
      "name": "EnterpriseRisks",
      "children": [
        {
          "RiskPortfolio": {
            "name": "OperationalRisks",
            "children": [...]
          }
        },
        ...
      ]
    }
  },
  "createdAt": "2026-01-04T00:20:00Z"
}
```

### 5. Compute Loss Exceedance Curve (LEC)
```bash
http POST localhost:8080/api/risktrees/123e4567-e89b-12d3-a456-426614174000/lec
```

Response includes quantiles and exceedance curve:
```json
{
  "riskTreeId": "123e4567-e89b-12d3-a456-426614174000",
  "quantiles": {
    "p50": 1234567.89,
    "p90": 3456789.01,
    "p95": 4567890.12,
    "p99": 7890123.45
  },
  "exceedanceCurve": [
    {"loss": 0, "probability": 1.0},
    {"loss": 100000, "probability": 0.98},
    {"loss": 500000, "probability": 0.85},
    {"loss": 1000000, "probability": 0.65},
    ...
    {"loss": 10000000, "probability": 0.01}
  ]
}
```

### 6. List all risk trees
```bash
http GET localhost:8080/api/risktrees
```

### 7. Delete a risk tree
```bash
http DELETE localhost:8080/api/risktrees/123e4567-e89b-12d3-a456-426614174000
```

## Supported Distributions

### Frequency Distributions
- **Poisson**: `{"distribution": "Poisson", "lambda": 5.0}`

### Severity Distributions
- **LogNormal**: `{"distribution": "LogNormal", "meanlog": 10.0, "sdlog": 1.5}`

## Validation Rules

1. **Name constraints**: 1-100 characters, alphanumeric + `_-` only
2. **nTrials**: Must be positive (typically 10,000-100,000)
3. **Mutually exclusive formats**: Provide either `root` (hierarchical) OR `risks` (flat), not both
4. **Non-empty**: At least one risk required
5. **Distribution parameters**: All numeric values must be valid for the distribution

## Error Responses

### Invalid request (empty risks):
```json
{
  "error": "At least one risk is required"
}
```

### Invalid name:
```json
{
  "error": "Name must be 1-100 characters and contain only alphanumeric, underscore, or hyphen"
}
```

### Missing format:
```json
{
  "error": "Either root or risks must be provided, but not both"
}
```

## Notes

- The hierarchical format (`root`) allows unlimited nesting depth
- Portfolios aggregate child risks
- Each leaf risk is simulated independently
- The LEC represents the aggregated loss distribution across all risks in the tree
- Response times scale with `nTrials` and tree complexity
