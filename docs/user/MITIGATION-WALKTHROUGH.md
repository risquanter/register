# Risquanter Register — Mitigation Walkthrough

This guide scripts the HTTP API through one complete sequence: create a tree, read it, change it and read its history, add mitigations, and compare the tree's figures with and without them. It is a way to try the mitigation features by hand. It is not an acceptance test.

There are two ways to do that. Run one of the two ready-made scripts, which do every step and print the figures. Or send the requests yourself, one step at a time, with the commands below. Each step says what to check in the answer. Then [step 11](#step-11--examine-the-mitigations-in-the-browser) examines the result in the browser.

For what each mitigation field means, see [API-TUTORIAL.md, section 4](API-TUTORIAL.md#4-mitigations). For the concepts, see [TERMINOLOGY.md, Part 3](TERMINOLOGY.md#part-3--mitigation) and [Part 4](TERMINOLOGY.md#part-4--valuing-a-tree-with-and-without-mitigation).

## What you need

| Requirement | Detail |
|---|---|
| A stack with the Irmin store | Step 4 reads the tree's history and step 5 forks a scenario. Both need the Irmin store, which keeps every saved version. The default in-memory mode keeps only the latest version. Start the persistent stack as [PERSISTENT-SETUP.md](PERSISTENT-SETUP.md) describes: `docker compose --profile persistence --profile frontend --env-file .env.irmin up -d --wait`. |
| API address | `http://localhost:18080` throughout. That is the frontend container, and its port is set in `docker-compose.yml`. It passes API requests on to the server. |
| An HTTP client | [curl](https://curl.se/) or [httpie](https://httpie.io/) (the `http` command). Every command is given for both, and both send the same requests. |
| `jq` | Reads values out of JSON answers and builds the requests that save a tree. |

## Run it all at once

The two scripts send the same requests and print the same lines. They differ only in the client they use.

Start the persistent stack first, then run one script from the repository root. `--wait` makes the command return only once the containers are up and healthy. Without it, the script can start before the server answers.

```bash
docker compose --profile persistence --profile frontend --env-file .env.irmin up -d --wait
./examples/demo-mitigations-curl.sh       # or
./examples/demo-mitigations-httpie.sh
```

If the stack is not up, the script stops at Step 1 with `No answer from http://localhost:18080`.

Each takes the API address as an optional argument. Each run creates a new workspace and ends with a summary:

```
  ✔  Workspace key : <workspace key>
  ✔  Tree ID       : <tree id>   (Regional Bank Risk Register)
  ✔  Branch main   : 3 commits, 0 mitigations
  ✔  with-controls : 7 commits, 4 mitigations
  ✔  Stale         : 1 after the re-estimate, 0 after re-affirming
  ✔  Enterprise, chance of $1M or more : inherent 0.4392, residual 0.2103
  ✔  Open in app   : http://localhost:18080/w/<workspace key>
```

The two probabilities differ from run to run. Every workspace draws its random numbers from its own [seed entity id](TERMINOLOGY.md#16-random-stream), so two workspaces holding the same tree give slightly different figures. Within one workspace the figures are the same every time they are read.

If you only want to see the result, go from the script straight to [step 11](#step-11--examine-the-mitigations-in-the-browser). The rest of this guide is the same sequence, sent by hand, one request at a time, in one terminal. Each step names the section of the two scripts that sends the same requests. In both scripts, the requests themselves are functions in the section headed `# ── Requests`, and the steps call them from the section headed `# ── Shared helpers` onward.

## The tree

The walkthrough builds this tree. A [portfolio](TERMINOLOGY.md#12-portfolio) is a node with children, and a leaf is one [risk](TERMINOLOGY.md#11-risk).

```
Enterprise (portfolio)
├── Cyber (portfolio)
│   ├── Ransomware            lognormal  15%  $400K–$9M
│   └── Data Breach           metalog    10%  P25=$500K P50=$2M P75=$6M P95=$20M
├── Operations (portfolio)
│   ├── Cloud Outage          lognormal  30%  $100K–$2M
│   ├── Payment Fraud         lognormal  25%  $50K–$1.5M
│   └── Key Supplier Failure  lognormal  12%  $300K–$5M
└── Compliance (portfolio)
    └── Regulatory Fine       lognormal   8%  $500K–$12M
```

The percentage is the leaf's likelihood. The range is its loss distribution, in one of the two forms [API-TUTORIAL.md](API-TUTORIAL.md#leaf-risk-parameters-a-modellers-guide) describes. All figures in this guide were chosen for the walkthrough.

---

## Step 1 — Create the workspace and the tree

**In the scripts:** the section that starts at `# ── Step 1:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L280) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L283).

One request creates a workspace and its first tree.

**curl:**
```bash
export BASE=http://localhost:18080

curl -s -X POST "$BASE/workspaces" -H 'Content-Type: application/json' -d '{
  "name": "Regional Bank Risk Register",
  "portfolios": [
    { "name": "Enterprise", "parentName": null },
    { "name": "Cyber",      "parentName": "Enterprise" },
    { "name": "Operations", "parentName": "Enterprise" },
    { "name": "Compliance", "parentName": "Enterprise" }
  ],
  "leaves": [
    { "name": "Ransomware", "parentName": "Cyber", "probability": 0.15,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 400000, "maxLoss": 9000000 } },
    { "name": "Data Breach", "parentName": "Cyber", "probability": 0.1,
      "distributionShape": { "distributionType": "Metalog",
        "percentiles": [0.25, 0.5, 0.75, 0.95],
        "quantiles": [500000, 2000000, 6000000, 20000000], "terms": null } },
    { "name": "Cloud Outage", "parentName": "Operations", "probability": 0.3,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 100000, "maxLoss": 2000000 } },
    { "name": "Payment Fraud", "parentName": "Operations", "probability": 0.25,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 50000, "maxLoss": 1500000 } },
    { "name": "Key Supplier Failure", "parentName": "Operations", "probability": 0.12,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 300000, "maxLoss": 5000000 } },
    { "name": "Regulatory Fine", "parentName": "Compliance", "probability": 0.08,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 500000, "maxLoss": 12000000 } }
  ],
  "newMitigations": []
}' | tee bootstrap.json | jq .
```

**httpie:**
```bash
export BASE=http://localhost:18080

http --ignore-stdin POST "$BASE/workspaces" \
  name="Regional Bank Risk Register" \
  portfolios:='[
    { "name": "Enterprise", "parentName": null },
    { "name": "Cyber",      "parentName": "Enterprise" },
    { "name": "Operations", "parentName": "Enterprise" },
    { "name": "Compliance", "parentName": "Enterprise" }
  ]' \
  leaves:='[
    { "name": "Ransomware", "parentName": "Cyber", "probability": 0.15,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 400000, "maxLoss": 9000000 } },
    { "name": "Data Breach", "parentName": "Cyber", "probability": 0.1,
      "distributionShape": { "distributionType": "Metalog",
        "percentiles": [0.25, 0.5, 0.75, 0.95],
        "quantiles": [500000, 2000000, 6000000, 20000000], "terms": null } },
    { "name": "Cloud Outage", "parentName": "Operations", "probability": 0.3,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 100000, "maxLoss": 2000000 } },
    { "name": "Payment Fraud", "parentName": "Operations", "probability": 0.25,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 50000, "maxLoss": 1500000 } },
    { "name": "Key Supplier Failure", "parentName": "Operations", "probability": 0.12,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 300000, "maxLoss": 5000000 } },
    { "name": "Regulatory Fine", "parentName": "Compliance", "probability": 0.08,
      "distributionShape": { "distributionType": "Lognormal", "minLoss": 500000, "maxLoss": 12000000 } }
  ]' \
  newMitigations:='[]' | tee bootstrap.json | jq .
```

httpie builds the JSON body from fields. `name=value` sends a string, and `name:=value` sends the value as JSON.

The answer:

```json
{
  "workspaceKey": "<workspace key>",
  "tree": { "id": "<tree id>", "name": "Regional Bank Risk Register" },
  "expiresAt": "2026-11-26T13:58:54.115765Z",
  "seedEntityId": 6
}
```

The workspace key is a [secret](TERMINOLOGY.md#82-secret): whoever holds it can read and change the workspace. Every later request carries it in its address. Keep it and the tree's id in two variables:

```bash
export WS_KEY=$(jq -r '.workspaceKey' bootstrap.json)
export TREE_ID=$(jq -r '.tree.id' bootstrap.json)
```

**Check:** the answer holds a `workspaceKey` and a `tree.id`.

### Four shell functions that shorten the later steps

This part is only for the by-hand route. The ready-made scripts do not need it, because each script defines its own versions of these requests in its `# ── Requests` section: `structure`, `send_tree`, `curves` and `exceedance`.

Steps 2 to 10 send four kinds of request again and again:

| Function | What it does | Used in |
|---|---|---|
| `read_tree <branch>` | reads the tree's structure | steps 2, 4, 5, 6, 7, 10 |
| `save_tree <branch>` | saves a new version of the tree, from a request body piped into it | steps 4, 6, 7, 10 |
| `figures <branch> <node ids> <selection>` | reads curves and summary figures for several nodes | steps 3, 8 |
| `chance <branch> <node id> <threshold> <selection>` | reads the chance that a node's loss reaches the threshold | steps 3, 4, 8 |

Writing out the full curl or httpie command each time would repeat the same address and headers in every step. A shell function gives that command a short name. You define each function once, and then call it by name like any other command.

A **branch** is one line of saved versions of the workspace's trees. `main` is the branch every workspace starts with. Every tree request names its branch in the `X-Branch` header, which is why each function takes the branch as its first argument.

**How to define them.** Paste the block for your client into the same terminal where you ran step 1. The functions use the variables `BASE`, `WS_KEY` and `TREE_ID` from step 1, so they work only in that terminal. Pasting prints nothing. The functions last until you close the terminal. If you open a new terminal, set the three variables again and paste the block again.

**curl:**
```bash
read_tree() { curl -s -H "X-Branch: $1" "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/structure"; }
save_tree() { curl -s -X PUT "$BASE/w/$WS_KEY/risk-trees/$TREE_ID" -H 'Content-Type: application/json' -H "X-Branch: $1" -d @-; }
figures()   { curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/lec-multi" -H 'Content-Type: application/json' -H "X-Branch: $1" -d "{\"nodeIds\": $2, \"selection\": $3}"; }
chance()    { curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/$2/prob-of-exceedance?threshold=$3" -H 'Content-Type: application/json' -H "X-Branch: $1" -d "{\"selection\": $4}"; }
```

**httpie:**
```bash
read_tree() { http --ignore-stdin GET "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/structure" "X-Branch:$1"; }
save_tree() { http PUT "$BASE/w/$WS_KEY/risk-trees/$TREE_ID" "X-Branch:$1"; }
figures()   { http --ignore-stdin POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/lec-multi" "X-Branch:$1" nodeIds:="$2" selection:="$3"; }
chance()    { http --ignore-stdin POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/$2/prob-of-exceedance?threshold=$3" "X-Branch:$1" selection:="$4"; }
```

**Check:** this call prints the tree's name. If it prints nothing or an error, `WS_KEY` or `TREE_ID` is not set in this terminal.

```bash
read_tree main | jq -r '.tree.name'
```

```
Regional Bank Risk Register
```

From here on the steps call these four functions, and a command is the same for both clients unless the step shows two.

## Step 2 — Read the structure and note the ids

**In the scripts:** the section that starts at `# ── Step 2:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L298) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L301).

The structure read returns every node with the id the server gave it.

```bash
read_tree main | jq -r '.tree.nodes[] | (.RiskPortfolio // .RiskLeaf) | "\(.id)  \(.name)"'
```

```
<Enterprise id>  Enterprise
<Cyber id>  Cyber
<Operations id>  Operations
<Compliance id>  Compliance
<Ransomware id>  Ransomware
…
```

Later requests name nodes by id. Read the five ids this guide uses into variables:

```bash
node_id() { read_tree main | jq -r --arg n "$1" '.tree.nodes[] | (.RiskPortfolio // .RiskLeaf) | select(.name == $n) | .id'; }
ROOT_ID=$(node_id "Enterprise")
CYBER_ID=$(node_id "Cyber")
RANSOM_ID=$(node_id "Ransomware")
FRAUD_ID=$(node_id "Payment Fraud")
FINE_ID=$(node_id "Regulatory Fine")
```

**Check:** ten nodes are listed, four portfolios and six leaves.

## Step 3 — Read the inherent figures

**In the scripts:** the section that starts at `# ── Step 3:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L319) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L322).

[Inherent risk](TERMINOLOGY.md#44-inherent-risk-and-residual-risk) is the loss before any mitigation is applied. A read asks for it with an empty [selection](TERMINOLOGY.md#45-selection), `{}`. The tree has no mitigation yet, so this is the only reading there is.

The curve read answers each node's [loss exceedance curve](TERMINOLOGY.md#111-loss-exceedance-curve) and a few summary figures. This `jq` filter keeps the summary figures:

```bash
SUMMARY='.curves[][] | { node: .curve.name, withMitigations: (.withMitigations | length),
                         averageAnnualLoss: .curve.averageAnnualLoss, P95: .curve.quantiles.P95, P99: .curve.quantiles.P99 }'

figures main "[\"$ROOT_ID\", \"$CYBER_ID\"]" '{}' | jq -c "$SUMMARY"
```

```json
{"node":"Enterprise","withMitigations":0,"averageAnnualLoss":2053940.7729,"P95":8073812.0,"P99":21239637}
{"node":"Cyber","withMitigations":0,"averageAnnualLoss":1176682.8592,"P95":5468626.0,"P99":18091611}
```

`averageAnnualLoss` is the node's loss averaged over every trial of the simulation. `P95` is the loss that 95% of the trials stay at or below. Enterprise loses about 2.05 million in an average year, and in one year in twenty it loses 8.07 million or more.

The second read answers one probability: the chance that a node's loss in the period is at or above a threshold. The threshold here is 1 million.

```bash
chance main "$ROOT_ID" 1000000 '{}' | jq -c .
```

```json
{"probability":0.3671,"withMitigations":[]}
```

**Check:** every reading has an empty `withMitigations`. Your figures differ from these in the later digits, because your workspace has another seed entity id.

## Step 4 — Change the tree twice and read its history

**In the scripts:** the section that starts at `# ── Step 4:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L326) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L329).

### How a tree is saved

A tree is saved whole. The request `PUT /w/{key}/risk-trees/{treeId}` replaces the stored tree with the one it carries. It lists every node to keep by its `id`, and a stored node the request does not name is deleted. Mitigations follow the same rule.

So a change has three parts: read the tree, turn the answer into a request that keeps everything, and change that request. The middle part is the same every time. This `jq` filter does it:

```bash
KEEP_ALL='
  (.tree.nodes | map(.RiskPortfolio // .RiskLeaf) | map({(.id): .name}) | add) as $names
  | {
      name: .tree.name,
      portfolios: [ .tree.nodes[] | .RiskPortfolio // empty
                    | {id, name, parentName: (if .parentId then $names[.parentId] else null end)} ],
      leaves: [ .tree.nodes[] | .RiskLeaf // empty
                | {id, name, parentName: $names[.parentId], probability, distributionShape: .shape} ],
      newPortfolios: [],
      newLeaves: [],
      mitigations: [ (.tree.mitigations // [])[]
                     | if .spec.stage == "LeafAbsolute"
                       then del(.target) | .spec |= (del(.baseStamp) + {stamp: {kind: "KeepIfUnchanged"}})
                       else . end ],
      newMitigations: []
    }'
```

The read names a node's parent by id, in `parentId`, and the request names it by name, in `parentName`. The filter translates one into the other. The read calls a leaf's loss distribution `shape`, and the request calls it `distributionShape`. The `mitigations` part is explained in step 7.

`read_tree main | jq "$KEEP_ALL"` is then a request that saves the tree unchanged. One more `jq` filter between the two makes the change.

### Two changes

Each save makes one **commit**: one stored version of the tree, which the store keeps for good.

Re-estimate Ransomware's likelihood from 15% to 25%:

```bash
read_tree main | jq "$KEEP_ALL" \
  | jq '.leaves |= map(if .name == "Ransomware" then .probability = 0.25 else . end)' \
  | save_tree main
```

Add a leaf, Phishing, under Cyber. A new node goes in `newLeaves` and carries no `id`:

```bash
read_tree main | jq "$KEEP_ALL" \
  | jq '.newLeaves = [ { name: "Phishing", parentName: "Cyber", probability: 0.35,
                         distributionShape: { distributionType: "Lognormal", minLoss: 20000, maxLoss: 400000 } } ]' \
  | save_tree main
```

Each save answers the tree's id and name:

```json
{"id":"<tree id>","name":"Regional Bank Risk Register"}
```

### The history

**curl:**
```bash
curl -s -H "X-Branch: main" "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/history" | jq -c '.entries[]'
```

**httpie:**
```bash
http --ignore-stdin GET "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/history" "X-Branch:main" | jq -c '.entries[]'
```

```json
{"commitHash":"7f1a5f19…","operation":{"kind":"TreeWrite","write":{"kind":"Create"}},"at":"2026-11-21T13:58:54Z"}
{"commitHash":"d400b0db…","operation":{"kind":"TreeWrite","write":{"kind":"Update"}},"at":"2026-11-21T13:58:57Z"}
{"commitHash":"2c821ced…","operation":{"kind":"TreeWrite","write":{"kind":"Update"}},"at":"2026-11-21T13:58:58Z"}
```

The entries are oldest first, and each `commitHash` names one version. The read returns up to 50 entries, a limit this project chose, unless the request sets `n`. Keep the first commit's hash:

**curl:**
```bash
C1=$(curl -s -H "X-Branch: main" "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/history" | jq -r '.entries[0].commitHash')
```

**httpie:**
```bash
C1=$(http --ignore-stdin GET "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/history" "X-Branch:main" | jq -r '.entries[0].commitHash')
```

### Reading an earlier version

Every read takes an optional `at` parameter holding a commit hash. With it the read answers that version, and without it the newest one.

The structure as it was created:

**curl:**
```bash
curl -s -H "X-Branch: main" "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/structure?at=$C1" \
  | jq -c '[.tree.nodes[] | .RiskLeaf // empty | {name, probability}]'
```

**httpie:**
```bash
http --ignore-stdin GET "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/structure" "X-Branch:main" at=="$C1" \
  | jq -c '[.tree.nodes[] | .RiskLeaf // empty | {name, probability}]'
```

The answer lists six leaves, with Ransomware at `0.15` and no Phishing.

Which nodes differ between that version and the newest one:

**curl:**
```bash
curl -s "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/changed-nodes?a=main&aAt=$C1&b=main" \
  | jq -c '.status.entries[] | select(.status != "Identical")'
```

**httpie:**
```bash
http --ignore-stdin GET "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/changed-nodes" a==main aAt=="$C1" b==main \
  | jq -c '.status.entries[] | select(.status != "Identical")'
```

```json
{"nodeId":"<Enterprise id>","status":"Changed"}
{"nodeId":"<Cyber id>","status":"Changed"}
{"nodeId":"<Ransomware id>","status":"Changed"}
{"nodeId":"<Phishing id>","status":"Added"}
```

Ransomware changed, Phishing was added, and the two portfolios above them changed because their children did.

The probability from step 3, at the first commit and now:

**curl:**
```bash
curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/$ROOT_ID/prob-of-exceedance?threshold=1000000&at=$C1" \
  -H 'Content-Type: application/json' -H "X-Branch: main" -d '{"selection": {}}' | jq -c .
```

**httpie:**
```bash
http --ignore-stdin POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/nodes/$ROOT_ID/prob-of-exceedance" "X-Branch:main" \
  threshold==1000000 at=="$C1" selection:='{}' | jq -c .
```

```bash
chance main "$ROOT_ID" 1000000 '{}' | jq -c .
```

```json
{"probability":0.3671,"withMitigations":[]}
{"probability":0.4375,"withMitigations":[]}
```

**Check:** the history lists three commits. The read at the first commit gives the same probability step 3 gave. The newest version gives a higher one, because Ransomware became likelier and Phishing was added.

## Step 5 — Fork a scenario

**In the scripts:** the section that starts at `# ── Step 5:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L359) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L362).

A **scenario** is a named branch that starts as a copy of another branch and is then changed apart from it. A scenario is not needed to compare figures with and without mitigations: one tree gives both readings, as step 8 shows. This guide uses one for a different reason. The mitigations are a proposal, and a scenario holds a proposal without changing `main`.

**curl:**
```bash
curl -s -X POST "$BASE/w/$WS_KEY/scenarios" -H 'Content-Type: application/json' \
  -d '{"name": "with-controls", "source": {"type": "Branch", "name": "main"}}' | jq -c .
```

**httpie:**
```bash
http --ignore-stdin POST "$BASE/w/$WS_KEY/scenarios" name="with-controls" source:='{"type": "Branch", "name": "main"}' | jq -c .
```

```json
{"name":"with-controls"}
```

From now on a request with `X-Branch: with-controls` reads and changes the scenario, and a request with `X-Branch: main` reads and changes `main`.

**Check:** `read_tree with-controls` answers the same seven leaves as `read_tree main`.

## Step 6 — Add three mitigations

**In the scripts:** the section that starts at `# ── Step 6:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L369) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L372).

A [mitigation](TERMINOLOGY.md#31-mitigation) is stored on the tree, so it is added with the same save as a node. A new one goes in `newMitigations`.

Each mitigation names the nodes it applies to with a [targeting predicate](TERMINOLOGY.md#38-targeting-predicate): a condition over one node, `x`, that names other nodes by id. The three used here:

| Predicate | Holds for |
|---|---|
| `leaf(x) /\ descendant_of(x, node_id("<Cyber id>"))` | every leaf under Cyber |
| `eq(x, node_id("<Cyber id>"))` | the Cyber portfolio itself |
| `eq(x, node_id("<Payment Fraud id>"))` | the Payment Fraud leaf |

The three mitigations:

| Name | What it claims | How it is written |
|---|---|---|
| MFA and awareness training | Every cyber event happens half as often. | `LeafRelative`, `likelihood` scaled by 0.5, on every leaf under Cyber |
| Cyber insurance | Of Cyber's total loss you bear the first 250 thousand, the insurer covers the next 5 million, and the rest is yours again. | `ResultStage`, one `InsurancePolicy` step, on the Cyber portfolio |
| Fraud monitoring | Every fraud loss is 60% of what it would have been. | `LeafRelative`, `severity` scaled by 0.6, on Payment Fraud |

The save is the one from step 4, on the scenario. The `--arg` lines hand the three predicates to `jq`, which writes them into the request as JSON strings:

```bash
read_tree with-controls | jq "$KEEP_ALL" | jq \
  --arg leavesUnderCyber "leaf(x) /\\ descendant_of(x, node_id(\"$CYBER_ID\"))" \
  --arg cyber "eq(x, node_id(\"$CYBER_ID\"))" \
  --arg fraud "eq(x, node_id(\"$FRAUD_ID\"))" \
  '.newMitigations = [
     { name: "MFA and awareness training",
       target: $leavesUnderCyber,
       spec: { stage: "LeafRelative", likelihood: { op: "Scale", factor: 0.5 }, severity: { op: "Keep" } },
       precedence: 0 },
     { name: "Cyber insurance",
       target: $cyber,
       spec: { stage: "ResultStage", pipeline: [ { op: "InsurancePolicy", deductible: 250000, limit: 5000000 } ] },
       precedence: 0 },
     { name: "Fraud monitoring",
       target: $fraud,
       spec: { stage: "LeafRelative", likelihood: { op: "Keep" }, severity: { op: "ScaleSeverity", factor: 0.6 } },
       precedence: 0 }
   ]' | save_tree with-controls
```

The server gives each mitigation an id. Read them back, with the nodes each one reaches:

```bash
read_tree with-controls | jq -c '.tree.mitigations[] | {id, name}'
read_tree with-controls | jq -c '.mitigationScopes[]'
```

```json
{"id":"<MFA id>","name":"MFA and awareness training"}
{"id":"<insurance id>","name":"Cyber insurance"}
{"id":"<fraud monitoring id>","name":"Fraud monitoring"}
{"mitigationId":"<MFA id>","outcome":{"kind":"Resolved","scope":["<Ransomware id>","<Data Breach id>","<Phishing id>"]}}
{"mitigationId":"<insurance id>","outcome":{"kind":"Resolved","scope":["<Cyber id>"]}}
{"mitigationId":"<fraud monitoring id>","outcome":{"kind":"Resolved","scope":["<Payment Fraud id>"]}}
```

`scope` is the mitigation's [resolved scope](TERMINOLOGY.md#39-resolved-scope): the nodes its predicate holds for in this version of the tree.

Keep the three ids:

```bash
mitigation_id() { read_tree with-controls | jq -r --arg n "$1" '.tree.mitigations[] | select(.name == $n) | .id'; }
MFA_ID=$(mitigation_id "MFA and awareness training")
INSURANCE_ID=$(mitigation_id "Cyber insurance")
FRAUD_MON_ID=$(mitigation_id "Fraud monitoring")
```

**Check:** the first mitigation's scope holds three leaves. Phishing is among them, although no request named it: it sits under Cyber, and the predicate is worked out again for every version.

## Step 7 — Add an absolute statement

**In the scripts:** the section that starts at `# ── Step 7:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L399) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L402).

The three mitigations above are [relative statements](TERMINOLOGY.md#36-relative-and-absolute-statements): each changes a figure by a fraction. An absolute statement says what one leaf's figure is after the control. This one says that after a remediation programme, Regulatory Fine's likelihood is 3%.

An absolute statement names its leaf in `anchor` and carries no `target`. The server writes the target from the anchor.

```bash
read_tree with-controls | jq "$KEEP_ALL" | jq --arg fine "$FINE_ID" \
  '.newMitigations = [
     { name: "Regulatory Fine after remediation",
       spec: { stage: "LeafAbsolute",
               replacement: { replaces: "Likelihood", probability: 0.03 },
               anchor: $fine,
               stamp: { kind: "KeepIfUnchanged" } },
       precedence: 1000 }
   ]' | save_tree with-controls

REMEDIATION_ID=$(mitigation_id "Regulatory Fine after remediation")
```

This save also kept the three mitigations from step 6. `KEEP_ALL` put each of them into `mitigations`, as the read returned it. A save that left them out would have deleted them.

Read the statement back:

```bash
read_tree with-controls | jq -c '.tree.mitigations[] | select(.spec.stage == "LeafAbsolute")'
```

```json
{"id":"<remediation id>","name":"Regulatory Fine after remediation","target":"eq(x, node_id(\"<Regulatory Fine id>\"))","spec":{"stage":"LeafAbsolute","replacement":{"replaces":"Likelihood","probability":0.03},"anchor":"<Regulatory Fine id>","baseStamp":"656cccdb…"},"precedence":1000}
```

The server added two things. `target` is the predicate it wrote from the anchor. `baseStamp` is the fingerprint of the leaf as it stood when the statement was saved, and step 10 uses it.

An absolute statement is the one mitigation that is not sent back as read. A save sends it without `target` and without `baseStamp`, and with a `stamp` that says what to do with the stored fingerprint. That is the `LeafAbsolute` branch of `KEEP_ALL`.

**Check:** the scenario holds four mitigations and `main` holds none:

```bash
read_tree with-controls | jq '.tree.mitigations | length'
read_tree main | jq '(.tree.mitigations // []) | length'
```

## Step 8 — Compare the figures with and without the mitigations

**In the scripts:** the section that starts at `# ── Step 8:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L425) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L428).

A selection says which mitigations a read applies. `fullScope` lists mitigations applied to every node in their scope. Listing all four gives [residual risk](TERMINOLOGY.md#44-inherent-risk-and-residual-risk), the loss after every mitigation.

```bash
ALL="{\"fullScope\": [\"$MFA_ID\", \"$INSURANCE_ID\", \"$FRAUD_MON_ID\", \"$REMEDIATION_ID\"]}"
```

### Both readings from one request

The curve read answers two readings for a node the selection reaches. The first has no mitigation applied, and the second has the selection applied. Both come from the same tree.

```bash
figures with-controls "[\"$ROOT_ID\", \"$CYBER_ID\"]" "$ALL" | jq -c "$SUMMARY"
```

```json
{"node":"Enterprise","withMitigations":0,"averageAnnualLoss":2397868.4034,"P95":8917341.0,"P99":21717725}
{"node":"Enterprise","withMitigations":4,"averageAnnualLoss":1029539.5274,"P95":3581722.0,"P99":1.116797E+7}
{"node":"Cyber","withMitigations":0,"averageAnnualLoss":1520610.4897,"P95":6649079.0,"P99":19144344}
{"node":"Cyber","withMitigations":2,"averageAnnualLoss":429251.2498,"P95":250000.0,"P99":6614644.0}
```

`withMitigations` counts the selected mitigations whose scope covers the node or a node below it. All four reach Enterprise. Two reach Cyber: the training, through Cyber's leaves, and the insurance.

Three things to read off these lines:

- Enterprise's average yearly loss falls from 2.40 million to 1.03 million.
- Cyber's `P95` with the mitigations is exactly 250 000, which is the policy's deductible. In a year when Cyber's total loss lies between 250 thousand and 5.25 million, the policy covers everything above the deductible, and you bear exactly the deductible. Many trials land in that range, they all record the same 250 000, and the 95th percentile falls among them.
- Cyber's `P99` with the mitigations is 6.61 million. In the worst years the total passes 5.25 million, the policy's 5 million limit is used up, and the rest comes back to you.

### One mitigation at a time

The probability read takes the same selection. Each line below is one request, for the chance that Enterprise loses 1 million or more:

```bash
chance with-controls "$ROOT_ID" 1000000 '{}' | jq -c .
chance with-controls "$ROOT_ID" 1000000 "{\"fullScope\": [\"$INSURANCE_ID\"]}" | jq -c .
chance with-controls "$ROOT_ID" 1000000 "{\"nodesOnly\": [{\"node\": \"$RANSOM_ID\", \"mitigation\": \"$MFA_ID\"}]}" | jq -c .
chance with-controls "$ROOT_ID" 1000000 "$ALL" | jq -c .
```

```json
{"probability":0.4375,"withMitigations":[]}
{"probability":0.286,"withMitigations":["<insurance id>"]}
{"probability":0.3624,"withMitigations":["<MFA id>"]}
{"probability":0.2013,"withMitigations":["<MFA id>","<insurance id>","<fraud monitoring id>","<remediation id>"]}
```

The first line is the inherent reading. The second applies the insurance alone, which answers what that one control is worth. The third uses `nodesOnly`: it applies the training at Ransomware only, and leaves Data Breach and Phishing as they are. The fourth is the residual reading.

### The mitigations belong to the scenario

Send the residual selection to `main`:

```bash
chance main "$ROOT_ID" 1000000 "$ALL" | jq .
```

The server answers `422`, and the body names each mitigation with the problem `MitigationNotFound`. The tree on `main` holds none of them.

**Check:** on `with-controls`, the empty selection gives the probability step 4 gave for the newest version of `main`. Adding mitigations did not change the inherent figures.

## Step 9 — Ask a screening query both ways

**In the scripts:** the section that starts at `# ── Step 9:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L445) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L448).

A screening query takes the selection as a word inside the query: `"inherent"` or `"residual"`. [VQL-QUERY-EXAMPLES.md](VQL-QUERY-EXAMPLES.md#the-mitigation-argument) describes the argument in full.

The question: do at least two thirds of the leaves have a 5% or higher chance of losing 500 thousand or more?

**curl:**
```bash
for which in inherent residual; do
  Q="Q[>=]^{2/3} x (leaf(x), gt_prob(lec(x, 500000, \"$which\"), 0.05))"
  curl -s -X POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/query" \
    -H 'Content-Type: application/json' -H "X-Branch: with-controls" \
    -d "{\"query\": $(echo "$Q" | jq -Rs .)}" | jq -c '{satisfied, proportion, satisfyingCount, rangeSize}'
done
```

**httpie:**
```bash
for which in inherent residual; do
  Q="Q[>=]^{2/3} x (leaf(x), gt_prob(lec(x, 500000, \"$which\"), 0.05))"
  http --ignore-stdin POST "$BASE/w/$WS_KEY/risk-trees/$TREE_ID/query" "X-Branch:with-controls" query="$Q" \
    | jq -c '{satisfied, proportion, satisfyingCount, rangeSize}'
done
```

```json
{"satisfied":true,"proportion":0.8571428571428571,"satisfyingCount":6,"rangeSize":7}
{"satisfied":false,"proportion":0.42857142857142855,"satisfyingCount":3,"rangeSize":7}
```

**Check:** six of the seven leaves pass the test with no mitigation, and three pass with all of them.

## Step 10 — Make the statement stale and re-affirm it

**In the scripts:** the section that starts at `# ── Step 10:` in [demo-mitigations-curl.sh](../../examples/demo-mitigations-curl.sh#L456) and [demo-mitigations-httpie.sh](../../examples/demo-mitigations-httpie.sh#L459).

The absolute statement from step 7 was written about Regulatory Fine as it stood then. Re-estimate that leaf, and send the statement back as it is. `KEEP_ALL` sends it with `"stamp": {"kind": "KeepIfUnchanged"}`, which keeps the stored fingerprint:

```bash
read_tree with-controls | jq "$KEEP_ALL" \
  | jq '.leaves |= map(if .name == "Regulatory Fine" then .distributionShape.maxLoss = 20000000 else . end)' \
  | save_tree with-controls

read_tree with-controls | jq -c '.staleMitigationIds'
```

```json
["<remediation id>"]
```

The statement is now [stale](TERMINOLOGY.md#36-relative-and-absolute-statements). Its stored fingerprint is the old leaf's, and the leaf has changed. It still applies to every read. The list tells its author that the figure was judged against a leaf that no longer has those figures.

To say the 3% still holds for the leaf as it now is, send the statement back with `"stamp": {"kind": "Restamp"}`. The server then records the leaf's current fingerprint:

```bash
read_tree with-controls | jq "$KEEP_ALL" \
  | jq '.mitigations |= map(if .name == "Regulatory Fine after remediation" then .spec.stamp = { kind: "Restamp" } else . end)' \
  | save_tree with-controls

read_tree with-controls | jq -c '.staleMitigationIds'
```

```json
[]
```

**Check:** the list holds one id after the first save and none after the second.

## Step 11 — Examine the mitigations in the browser

The browser shows mitigations, applies them and compares their figures, but it cannot create one (at the time of writing). That is why steps 1 to 10 build the data through the API. This step starts from what the script leaves behind: the tree on `main` with three commits, and the scenario `with-controls` with seven commits and four mitigations.

### Open the workspace

The script's last lines print the address to open:

```
  ✔  Open in app   : http://localhost:18080/w/<workspace key>
```

Open it, and choose **Analyze** in the left sidebar. The Analyze view has the chart and the query pane on the left, and the **Load Trees** panel on the right.

The first card in **Load Trees** is the baseline card. It has two selects: the tree first, then the scenario. Choose **Regional Bank Risk Register** and `with-controls`. The card opens when the tree has loaded. The scenario select appears only on the Irmin stack, because the in-memory stack has no scenarios.

**Check:** the card shows the tree with its seven leaves.

### The mitigation lines at the top of the card

Above the tree, the card lists the tree's four mitigations, one line each. A line holds a checkbox, the mitigation's name, and its stage. The stage reads `parameter stage` for a mitigation that changes a leaf's figures, and `result stage` for one that acts on a node's simulated losses, such as the insurance policy.

Hover over a line to see its predicate, the text that says which nodes the mitigation reaches. A `⚠` mark on a line means the predicate matched no node in this version of the tree. A `stale` badge marks an absolute statement whose leaf has changed since the statement was written.

**Check:** the four lines are `MFA and awareness training`, `Cyber insurance`, `Fraud monitoring` and `Regulatory Fine after remediation`. None carries `⚠` or `stale`. Switch **Scenario** to `main`, and the card lists no mitigations. Switch back to `with-controls`.

### Where each mitigation applies

Each node row can carry a `◆` mark. A solid `◆` means a mitigation is scoped at that node. A faint `◆` means a mitigation is scoped only at a node below it. Hover over the mark to see the mitigations' names.

Under each node a mitigation is scoped at, the card adds one row per mitigation, one level further in than the node. The row has a shield icon and the mitigation's name, and it behaves like a node row. While the mitigation is applied at that node and the node is on the chart, the row carries the node's curve colour as its left border.

**Check:** `Cyber` carries a solid `◆` for `Cyber insurance`. `Ransomware`, `Data Breach` and `Phishing` each carry a row for `MFA and awareness training`. `Enterprise` carries a faint `◆`.

### Put curves on the chart

The chart draws the curves of the nodes you choose. Ctrl+click a node row to add its curve, and Ctrl+Shift+click to remove it. On a Mac, use Cmd in place of Ctrl. The chart holds at most 13 nodes you have chosen this way.

Ctrl+click `Enterprise` and `Cyber`.

The baseline card has a three-way control: **Inherent**, **Mitigated** and **Both**. **Inherent** draws each node's figures with no mitigation applied. **Mitigated** draws them with the ticked mitigations applied. **Both** draws the two together, and is where the baseline card starts. A mitigated curve has `(mitigated)` after the node's name in the legend, and triangle markers in place of circles.

**Check:** with no checkbox ticked, each node has one curve, because no mitigation is applied yet.

### Apply mitigations and watch the curves move

Tick `MFA and awareness training`. The checkbox applies it everywhere in its scope. A second curve appears for `Enterprise` and for `Cyber`, marked `(mitigated)`, and it lies below the inherent curve. A lower curve means a smaller chance of each loss.

Tick `Cyber insurance` as well. The mitigated `Cyber` curve drops further, because the insurance pays part of every Cyber loss above the 250 thousand deductible.

Untick both. The mitigated curves disappear.

**Check:** ticking any of the four mitigations moves the `Enterprise (mitigated)` curve below `Enterprise`. Step 8 printed the same comparison as numbers.

### Apply a mitigation at one node only

A mitigation row takes the same clicks as a node row. Ctrl+click applies the mitigation at that node, and puts the node on the chart if it is not there. Ctrl+Shift+click stops applying it at that node. On a Mac, use Cmd in place of Ctrl.

Ctrl+click the `MFA and awareness training` row under `Ransomware`. `Ransomware` is added to the chart with an inherent and a mitigated curve, and the row takes `Ransomware`'s curve colour as its border. The rows under `Data Breach` and `Phishing` have no border. The checkbox at the top of the card shows a dash, the browser's mark for a box that is neither on nor off, because the mitigation is applied at part of its scope.

This is the browser's form of the `nodesOnly` selection of step 8.

**Check:** the `Enterprise (mitigated)` curve moves less than it did with the whole mitigation ticked.

Ctrl+Shift+click the row to stop applying the mitigation there. Its border goes, and the mitigated curves go with it.

### Go back in the history

Under the two selects, the card has a row of stops, one per commit on the branch. The oldest is on the left, and the rightmost is the latest. Hover over a stop to see its time and commit hash. Click a stop to show the tree as it was at that commit.

On `with-controls` there are seven stops. The first three are the commits made on `main` before the fork. The four after them are the scenario's own commits, step 6 to step 10.

Tick `Cyber insurance`, then click the third stop. The tree at that commit has no mitigations, so the lines at the top disappear. A notice over the chart says `1 selected mitigation(s) not present at this point in time`. Click the sixth stop, which is the re-estimate of step 10. `Regulatory Fine after remediation` carries the `stale` badge there. Click the rightmost stop to return to the latest version.

**Check:** the badge shows at the sixth stop and not at the seventh, as step 10 reported.

### Compare the scenario with main

Click **+ Compare tree** at the bottom of **Load Trees**. A second card appears. In its selects, choose the same tree and `main`.

The chart now draws both cards. The toggle at the bottom of **Load Trees** chooses **Overlay**, with both cards on one chart, or **Side by side**, with one chart per card. A node that differs between the two cards carries a `✎` mark. On this tree that is `Regulatory Fine`, whose maximum loss step 10 changed on the scenario, and `Compliance` and `Enterprise` above it.

The second card starts on **Mitigated**, and has no mitigations to tick on `main`. Its mirror button makes the card chart the same nodes as the baseline card. It copies the charted nodes, not the ticked mitigations.

**Check:** with `MFA and awareness training` ticked on the baseline card, the baseline's mitigated `Enterprise` curve lies below the `main` card's `Enterprise` curve.

Remove the second card with its `−` button.

### Ask a screening query

The query pane is on the left, above the chart. Type the query of step 9 into **Query Expression**:

```
Q[>=]^{2/3} x (leaf(x), gt_prob(lec(x, 500000, "residual"), 0.05))
```

Click **Run**, or press Ctrl+Enter. The answer says whether the query holds. The leaves that satisfy it are marked in the tree, and their curves are added to the chart. Replace `"residual"` with `"inherent"` and run it again.

**Check:** the two runs give the two answers step 9 printed.

### Re-affirm a stale statement in the browser

The script re-affirmed the statement through the API. To do it in the browser, first make the statement stale again:

1. Choose **Design** in the sidebar. In the **Scenarios:** toolbar, switch to `with-controls`. Under **Saved Trees**, choose the tree.
2. Click `Regulatory Fine` in the tree preview. Its form opens locked. Click **Edit**, change its maximum loss, and click **Save**. Then click **Update Risk Tree**.
3. Under the tree preview, the **Absolute statements** list shows `Regulatory Fine after remediation` with the `stale` badge.
4. Click **Edit** on that row. A window opens, titled `Review the figures of 'Regulatory Fine after remediation'`. It shows the statement's likelihood, 3%.
5. Click **Apply**. The row now carries a `●` mark, which means re-affirmed but not saved. Click **Update Risk Tree** to save it.

On the Analyze view the `stale` badge on the line at the top of the card is a link. It opens the same window in the Design view, on the same branch.

**Check:** after **Update Risk Tree**, the badge is gone, both in the Design view and on the Analyze card.

---

## When a request is refused

A refusal's status says which part of the request the server could not accept.

| Status | Meaning | Example from this walkthrough |
|---|---|---|
| `400` | The request could not be read. | A tree request with no `X-Branch` header. The body names the header and the problem `Required`. |
| `422` | The request was read, and one or more of its entries was refused. The body lists every refused entry with its location. | A `factor` of `0` in a new mitigation. The body names the entry, the field `Factor` and the problem `OutOfRange`. |

Two refusals come from the setup and not from a request.

**The server runs without the Irmin store.** The in-memory mode keeps only the newest version of each tree, on `main` only. The scripts stop in step 4 and print `Commit history needs the Irmin backend`. By hand, the history read answers no entries, and the scenario request is refused. Start the persistent stack named under "What you need".

**A save left out a stored mitigation.** A save deletes every stored mitigation its `mitigations` list does not name. A later read then answers `422` with `MitigationNotFound` for a selection that names it. Build every save from `KEEP_ALL`, which keeps them all.
