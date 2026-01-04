# Manual API Test Log
**Date**: January 4, 2026  
**Server**: localhost:8080  
**Purpose**: Validate LEC endpoints with Iron validation

---

## Test Case 1: Health Check
**Endpoint**: `GET /health`  
**Expected**: Server responds (may be empty body)

```bash
http GET localhost:8080/health
```

**Result**:

---

## Test Case 2: Create Simple Risk Tree (Flat Structure)
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test backward-compatible flat structure  
**Validation**: name (non-blank), nTrials (positive), probability (0-1), distributionType

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "Simple Risk Tree",
  "nTrials": 5000,
  "risks": [
    {
      "name": "Cyber Attack",
      "distributionType": "lognormal",
      "probability": 0.75,
      "minLoss": 1000,
      "maxLoss": 50000
    },
    {
      "name": "Data Breach",
      "distributionType": "lognormal",
      "probability": 0.60,
      "minLoss": 2000,
      "maxLoss": 80000
    }
  ]
}
EOF
```

**Expected**: 
- Status: 200 OK
- Response contains: `id`, `name`, `quantiles` (empty initially)

**Result**:

---

## Test Case 3: Compute LEC with Default Depth (depth=0)
**Endpoint**: `GET /risk-trees/:id/lec`  
**Purpose**: Test LEC generation for root only  
**Validation**: depth defaults to 0

```bash
http GET localhost:8080/risk-trees/1/lec
```

**Expected**:
- Status: 200 OK
- `vegaLiteSpec`: Contains Vega-Lite JSON with 100 curve points
- `quantiles`: Contains p50, p90, p95, p99
- `depth`: 0
- Vega spec should contain only `"risk": "root"` entries
- No child risk names in spec

**Result**:

---

## Test Case 4: Create Hierarchical Risk Tree
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test hierarchical structure with nested portfolios  
**Validation**: Recursive validation of all nodes

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "Enterprise Risk Portfolio",
  "nTrials": 5000,
  "root": {
    "id": "enterprise-risk",
    "name": "Enterprise Risk",
    "children": [
      {
        "id": "operational",
        "name": "Operational Risk",
        "children": [
          {
            "id": "cyber",
            "name": "Cyber Attack",
            "distributionType": "lognormal",
            "probability": 0.80,
            "minLoss": 5000,
            "maxLoss": 100000
          },
          {
            "id": "fraud",
            "name": "Fraud",
            "distributionType": "lognormal",
            "probability": 0.65,
            "minLoss": 3000,
            "maxLoss": 60000
          }
        ]
      },
      {
        "id": "strategic",
        "name": "Strategic Risk",
        "children": [
          {
            "id": "market-disruption",
            "name": "Market Disruption",
            "distributionType": "lognormal",
            "probability": 0.45,
            "minLoss": 10000,
            "maxLoss": 200000
          }
        ]
      }
    ]
  }
}
EOF
```

**Expected**:
- Status: 200 OK
- Response contains hierarchical structure
- `id`: 2

**Result**:

---

## Test Case 5: LEC with Depth=1 (Root + Children)
**Endpoint**: `GET /risk-trees/:id/lec?depth=1`  
**Purpose**: Test hierarchical LEC with one level of children  
**Validation**: depth parameter validation (non-negative)

```bash
http GET localhost:8080/risk-trees/2/lec depth==1
```

**Expected**:
- Status: 200 OK
- `depth`: 1
- Vega spec contains:
  - `"risk": "enterprise-risk"` (root)
  - `"risk": "operational"` (child 1)
  - `"risk": "strategic"` (child 2)
- NO grandchildren (cyber, fraud, market-disruption)
- Multiple curves with different colors

**Result**:

---

## Test Case 6: LEC with Depth=2 (3 Levels)
**Endpoint**: `GET /risk-trees/:id/lec?depth=2`  
**Purpose**: Test deeper hierarchy

```bash
http GET localhost:8080/risk-trees/2/lec depth==2
```

**Expected**:
- Status: 200 OK
- `depth`: 2
- Vega spec contains all risks: root, portfolios, and leaf nodes
- 5 curves total (1 root + 2 portfolios + 3 leaves)

**Result**:

---

## Test Case 7: LEC with Excessive Depth (Clamping)
**Endpoint**: `GET /risk-trees/:id/lec?depth=99`  
**Purpose**: Test depth clamping to max of 5  
**Validation**: depth clamped but no error

```bash
http GET localhost:8080/risk-trees/2/lec depth==99
```

**Expected**:
- Status: 200 OK
- `depth`: Should show actual depth used (clamped to 5)
- No error, just clamped value

**Result**:

---

## Test Case 8: Invalid Depth (Negative)
**Endpoint**: `GET /risk-trees/:id/lec?depth=-1`  
**Purpose**: Test Iron validation for negative depth  
**Validation**: Non-negative integer constraint

```bash
http GET localhost:8080/risk-trees/2/lec depth==-1
```

**Expected**:
- Status: 400 Bad Request
- Error message: Contains "depth" and "non-negative" or ">= 0"

**Result**:

---

## Test Case 9: Invalid Request - Empty Name
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test Iron validation for name field  
**Validation**: SafeName (non-blank, max 50 chars)

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "",
  "nTrials": 1000,
  "risks": [
    {
      "name": "Test Risk",
      "distributionType": "lognormal",
      "probability": 0.5,
      "minLoss": 1000,
      "maxLoss": 10000
    }
  ]
}
EOF
```

**Expected**:
- Status: 400 Bad Request
- Error contains: "Name" constraint violation

**Result**:

---

## Test Case 10: Invalid Request - Zero nTrials
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test Iron validation for nTrials  
**Validation**: PositiveInt (must be > 0)

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "Invalid Trials",
  "nTrials": 0,
  "risks": [
    {
      "name": "Test Risk",
      "distributionType": "lognormal",
      "probability": 0.5,
      "minLoss": 1000,
      "maxLoss": 10000
    }
  ]
}
EOF
```

**Expected**:
- Status: 400 Bad Request
- Error contains: "nTrials" and "positive" or "> 0"

**Result**:

---

## Test Case 11: Invalid Request - Probability Out of Range
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test Iron validation for probability  
**Validation**: Probability (0.0 < p < 1.0)

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "Invalid Probability",
  "nTrials": 1000,
  "risks": [
    {
      "name": "Test Risk",
      "distributionType": "lognormal",
      "probability": 1.5,
      "minLoss": 1000,
      "maxLoss": 10000
    }
  ]
}
EOF
```

**Expected**:
- Status: 400 Bad Request
- Error contains: "probability" constraint violation

**Result**:

---

## Test Case 12: Invalid Request - Wrong Distribution Type
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test Iron validation for distributionType  
**Validation**: DistributionType (must be "expert" or "lognormal")

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "Invalid Distribution",
  "nTrials": 1000,
  "risks": [
    {
      "name": "Test Risk",
      "distributionType": "normal",
      "probability": 0.5,
      "minLoss": 1000,
      "maxLoss": 10000
    }
  ]
}
EOF
```

**Expected**:
- Status: 400 Bad Request
- Error contains: "distribution type" and "expert" or "lognormal"

**Result**:

---

## Test Case 13: Invalid Request - Negative Loss Values
**Endpoint**: `POST /risk-trees`  
**Purpose**: Test Iron validation for loss values  
**Validation**: NonNegativeLong (>= 0)

```bash
cat <<'EOF' | http POST localhost:8080/risk-trees
{
  "name": "Negative Loss",
  "nTrials": 1000,
  "risks": [
    {
      "name": "Test Risk",
      "distributionType": "lognormal",
      "probability": 0.5,
      "minLoss": -1000,
      "maxLoss": 10000
    }
  ]
}
EOF
```

**Expected**:
- Status: 400 Bad Request
- Error contains: "minLoss" and "non-negative"

**Result**:

---

## Test Case 14: List All Risk Trees
**Endpoint**: `GET /risk-trees`  
**Purpose**: Test retrieval of all trees

```bash
http GET localhost:8080/risk-trees
```

**Expected**:
- Status: 200 OK
- Array of risk trees (at least 2 from previous tests)
- Each contains: `id`, `name`, `nTrials`

**Result**:

---

## Test Case 15: Get Single Risk Tree by ID
**Endpoint**: `GET /risk-trees/:id`  
**Purpose**: Test retrieval of single tree

```bash
http GET localhost:8080/risk-trees/2
```

**Expected**:
- Status: 200 OK
- Single risk tree object with hierarchical structure

**Result**:

---

## Test Case 16: Vega-Lite JSON Structure Validation
**Endpoint**: Extract vegaLiteSpec from LEC response  
**Purpose**: Verify Vega-Lite structure is valid

```bash
http GET localhost:8080/risk-trees/2/lec depth==2 | jq -r '.exceedanceCurve' > vega-spec.json
cat vega-spec.json | jq . | head -50
```

**Expected**:
- Valid JSON
- Contains: `$schema`, `width`, `height`, `data`, `mark`, `encoding`
- `data.values`: Array of objects with `risk`, `loss`, `exceedance`
- `mark.type`: "line"
- `mark.interpolate`: "basis"
- `encoding.color`: Color scale with risk names
- Axis formatting with labelExpr for B/M notation

**Result**:

---

## Test Case 17: Verify Quantiles Calculation
**Endpoint**: `GET /risk-trees/:id/lec`  
**Purpose**: Verify quantiles are calculated correctly

```bash
http GET localhost:8080/risk-trees/1/lec | jq '.quantiles'
```

**Expected**:
- Object with keys: `p50`, `p90`, `p95`, `p99`
- Values are numeric (loss amounts)
- Ascending order: p50 < p90 < p95 < p99

**Result**:

---

## Test Case 18: Override nTrials in LEC Computation
**Endpoint**: `GET /risk-trees/:id/lec?nTrials=10000`  
**Purpose**: Test nTrials override parameter  
**Validation**: PositiveInt validation

```bash
http GET localhost:8080/risk-trees/1/lec nTrials==10000
```

**Expected**:
- Status: 200 OK
- Results may differ from previous run (more trials = more precision)

**Result**:

---

## Test Case 19: Invalid nTrials Override
**Endpoint**: `GET /risk-trees/:id/lec?nTrials=0`  
**Purpose**: Test validation of nTrials override

```bash
http GET localhost:8080/risk-trees/1/lec nTrials==0
```

**Expected**:
- Status: 400 Bad Request
- Error contains: "nTrials" and "positive"

**Result**:

---

## Test Case 20: Non-Existent Risk Tree
**Endpoint**: `GET /risk-trees/999/lec`  
**Purpose**: Test error handling for missing tree

```bash
http GET localhost:8080/risk-trees/999/lec
```

**Expected**:
- Status: 400 Bad Request
- Error message: "RiskTree with id=999 not found"

**Result**:

---

## Summary Statistics

**Total Tests**: 20  
**Passed**: ___  
**Failed**: ___  

**Categories**:
- Validation (Iron constraints): 8 tests
- LEC computation: 6 tests
- Hierarchical structure: 3 tests
- CRUD operations: 3 tests

---

## Notes for User Guide

### Key Validation Rules Discovered:
1. **name**: Non-blank, max 50 characters
2. **nTrials**: Must be positive integer (> 0)
3. **probability**: Must be between 0.0 and 1.0 (exclusive)
4. **distributionType**: Must be "expert" or "lognormal"
5. **minLoss/maxLoss**: Must be non-negative (>= 0)
6. **depth**: Must be non-negative (>= 0), automatically clamped to max 5
7. **parallelism**: Must be positive integer (> 0)

### Common Error Patterns:
- Empty or whitespace-only strings rejected
- Boundary values (0, 1.0, negative numbers) properly validated
- Clear error messages with field names

### Vega-Lite Spec Features:
- 100 curve points per risk
- BCG color palette (10 colors)
- Smooth B-spline curves
- Axis formatting for billions/millions
- Multiple curves for hierarchical views

---

## Comparison with Automated Tests

### Coverage Overlap:
- [x] Request validation
- [x] Hierarchical structure creation
- [x] LEC computation with depth
- [x] Error handling

### Manual Testing Additions:
- [ ] Actual HTTP responses
- [ ] JSON structure validation
- [ ] Vega-Lite spec correctness
- [ ] End-to-end workflow
- [ ] Multiple error scenarios

### Automated Testing Advantages:
- [ ] Edge cases (empty arrays, null handling)
- [ ] Concurrent execution
- [ ] Repository behavior
- [ ] Statistical correctness (quantiles)
