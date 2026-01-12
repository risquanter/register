# LEC Computation Failure - Root Cause Analysis

**Date:** 2026-01-12  
**Issue:** Native image fails to execute Monte Carlo simulations (LEC computation)  
**Status:** ROOT CAUSE IDENTIFIED

---

## Stack Trace

```
[ERROR] Unhandled exception in HTTP layer:
java.lang.RuntimeException: Tree simulation failed for simulationId=tree-1: null
        at com.risquanter.register.services.SimulationExecutionServiceLive.runTreeSimulation$$anonfun$1(SimulationExecutionService.scala:50)
```

**Key Observation:** Error message is `null` - underlying exception has no message, indicating a NullPointerException or similar low-level failure.

---

## Call Chain Analysis

```
HTTP Request (GET /risk-trees/1/lec)
  ↓
RiskTreeController.computeLEC
  ↓
RiskTreeServiceLive.computeLECInternal
  ↓
SimulationExecutionServiceLive.runTreeSimulation
  ↓
Simulator.simulateTree
  ↓
Simulator.simulateTreeInternal (for RiskLeaf)
  ↓
Simulator.createSamplerFromLeaf
  ↓
Simulator.createDistributionWithParams
  ↓
MetalogDistribution.fromPercentiles OR LognormalHelper.fromLognormal90CI
  ↓
**FAILURE HERE** - Breeze/Apache Commons Math native library call
```

---

## Root Cause: Native Image Reflection/JNI Issues

### Mathematical Libraries Involved

1. **ojAlgo** (Pure Java linear algebra and optimization)
   - Version: 56.0.0
   - Used for: Quadratic programming optimization in `QPUnboundedConstrainedFitter`
   - Location: `simulation.util` library (com.risquanter:simulation.util:0.8.0)
   - Likely failure point: QP solver initialization or matrix operations

2. **Apache Commons Math3**
   - Version: 3.6.1
   - Used for: Statistical distributions (LogNormalDistribution)
   - Location: `com.risquanter.register.simulation.LognormalHelper`
   - Reflection: Distribution classes may use reflection for parameters

3. **Metalog Distribution**
   - Custom implementation in `simulation.util` library
   - Uses: ojAlgo for quantile parameter fitting via quadratic programming
   - Called from: `com.risquanter.register.simulation.MetalogDistribution`
   - Likely failure point: QPFitter calling ojAlgo optimization

---

## Native Image Configuration Gap

### Missing Configurations

**Reflection Config (`reflect-config.json`):**
- ❌ ojAlgo optimization classes not registered
- ❌ Apache Commons Math distribution classes not registered

**JNI Config (`jni-config.json`):**
- Likely N/A - ojAlgo is pure Java (no native dependencies)

**Runtime Initialization (`--initialize-at-run-time`):**
- ❌ ojAlgo static initializers may run at build time
- ❌ Random number generator initialization timing issue
- ❌ Apache Commons Math RNG initialization

---

## Evidence from Code

### Test Data (create-simple-risk.json)
```json
{
  "root": {
    "RiskLeaf": {
      "distributionType": "expert",
      "percentiles": [0.05, 0.5, 0.95],
      "quantiles": [100000, 500000, 2000000]
    }
  }
}
```

**Flow:**
1. `distributionType = "expert"` triggers `MetalogDistribution.fromPercentiles()`
2. This calls `QPFitter.with().fit()` from simulation.util library
3. QPFitter uses ojAlgo's `ExpressionsBasedModel` for quadratic programming
4. **Native image fails** - likely reflection in ojAlgo optimization or initialization issue

---

## Why JVM Works But Native Image Fails

| Aspect | JVM | Native Image |
|--------|-----|--------------|
| Reflection | Dynamic at runtime | Must be pre-configured |
| Class loading | On-demand | All classes compiled ahead-of-time |
| ojAlgo QP | Works out of the box | Requires explicit configuration |
| Commons Math | Works out of the box | May need reflection config |

---

## Fix Strategy

### Phase 1: Use Native Image Tracing Agent

**Step 1: Run with tracing agent in JVM mode**
```bash
# Start server with agent
java -agentlib:native-image-agent=config-output-dir=native-config \
  -jar modules/server/target/scala-3.5.2/register-server.jar &

# Exercise LEC endpoint
curl -X POST http://localhost:8080/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-simple-risk.json

curl "http://localhost:8080/risk-trees/1/lec?depth=0&nTrials=1000"

# Stop server - check native-config/ directory
ls -la native-config/
```

**Expected Output Files:**
- `reflect-config.json` - All reflected classes
- `jni-config.json` - JNI methods
- `resource-config.json` - Loaded resources
- `proxy-config.json` - Dynamic proxies
- `serialization-config.json` - Serialized classes

---

### Phase 2: Add Configurations to native-image.properties

**Location:** `modules/server/src/main/resources/META-INF/native-image/`

**reflect-config.json additions (expected):**
```json
[
  {
    "name": "org.ojalgo.optimisation.ExpressionsBasedModel",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.ojalgo.optimisation.Expression",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "org.ojalgo.optimisation.Variable",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "org.apache.commons.math3.distribution.LogNormalDistribution",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  }
]
```

**native-image.properties additions:**
```properties
Args = --initialize-at-run-time=org.ojalgo \
       --initialize-at-run-time=org.apache.commons.math3.random \
       --initialize-at-run-time=scala.util.Random
```

---

### Phase 3: Alternative - Pure Java Implementation

If native libraries prove too complex:

**Option A: Use simpler optimization**
- Replace QP solver with closed-form solution where possible
- Simplify Metalog fitting algorithm

**Option B: Simplify Distribution Fitting**
- Pre-compute Metalog coefficients in JVM
- Store as JSON resource, load at runtime

**Option C: External Simulation Service**
- Keep simulation in JVM-based microservice
- Native image calls via HTTP/gRPC

---

## Testing Checklist

Once configurations applied:

- [ ] Build native image successfully
- [ ] Test LEC with expert distribution (percentiles/quantiles)
- [ ] Test LEC with lognormal distribution (minLoss/maxLoss)
- [ ] Test hierarchical risk trees (RiskPortfolio with children)
- [ ] Verify provenance metadata generation
- [ ] Run full test suite (143 tests)
- [ ] Performance benchmark (compare JVM vs native)

---

## Expected Timeline

| Phase | Time | Notes |
|-------|------|-------|
| Tracing agent | 30 min | Generate configs |
| Apply configs | 1 hour | Merge into project |
| Rebuild & test | 30 min | Native image build |
| Debug & iterate | 2-4 hours | Fix remaining issues |
| **Total** | **4-6 hours** | Realistic estimate |

---

## Fallback Plan

If native image configuration proves too complex (>8 hours):

1. **Hybrid Deployment:**
   - Native image for API endpoints (CRUD)
   - JVM sidecar for simulations
   - Keep fast startup + full simulation capabilities

2. **Defer Migration:**
   - Complete CRUD migration to native
   - Keep simulation on JVM temporarily
   - Revisit after GraalVM improvements

3. **Simplified Distributions:**
   - Remove Metalog (expert mode) temporarily
   - Keep only lognormal (no QP solver needed)
   - Reduces ojAlgo dependency

---

## Next Actions

1. ✅ **DONE:** Capture stack trace with debug logging
2. ✅ **DONE:** Identify call chain to failure point
3. **NEXT:** Run native-image tracing agent
4. Apply generated configurations
5. Rebuild and revalidate
6. Update VALIDATION-RESULTS.md with outcome
