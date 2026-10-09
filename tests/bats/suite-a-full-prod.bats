#!/usr/bin/env bats
# ============================================================================
# Suite A — End-to-End: Frontend → Server → Irmin
# ============================================================================
# Compose: local/frontend:dev + register-server:prod + local/irmin-prod:3.11-p1
# Profile: --profile persistence --profile frontend
# Purpose: True end-to-end validation. The server is configured with
#          REGISTER_REPOSITORY_TYPE=irmin so workspace data flows through
#          nginx → register-server → Irmin. Tests verify that data created
#          via the public API is actually persisted in Irmin by querying
#          Irmin's GraphQL endpoint directly.
#
# Run:     bats tests/bats/suite-a-full-prod.bats
# Prereq:  Images built: register-server:prod, local/frontend:dev,
#          local/irmin-prod:3.11-p1 (and local/irmin-builder:3.11-p1)
# ============================================================================

# Irmin host port — matches docker-compose.yml: "9080:8080"
IRMIN_DIRECT_PORT="9080"
IRMIN_DIRECT_URL="http://localhost:${IRMIN_DIRECT_PORT}"

setup_file() {
    # Wire the server to Irmin for real persistence (default is in-memory).
    export REGISTER_REPOSITORY_TYPE=irmin
    # Compose-internal URL: the server container resolves 'irmin' via Docker DNS.
    export IRMIN_URL="http://irmin:8080"

    docker compose --profile persistence --profile frontend up -d --wait 2>&1 || {
        echo "Failed to start compose services" >&2
        docker compose --profile persistence --profile frontend logs 2>&1
        return 1
    }

    load helpers/setup

    wait_for_graphql "${IRMIN_DIRECT_URL}/graphql" 30
    wait_for_url "${REGISTER_URL}/health" 30
    wait_for_url "${FRONTEND_URL}/" 30
}

teardown_file() {
    docker compose --profile persistence --profile frontend down -v 2>&1
}

setup() {
    load helpers/setup
}

# ============================================================================
# Service health — all three containers operational
# ============================================================================

@test "A01: all services healthy — Irmin, server, frontend" {
    # Irmin GraphQL
    local irmin_status
    irmin_status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X POST "${IRMIN_DIRECT_URL}/graphql" \
        -H 'Content-Type: application/json' \
        -d '{"query":"{ __typename }"}')
    [[ "$irmin_status" == "200" ]]

    # Register server
    local server_status server_body
    server_status=$(curl -s -o /dev/null -w '%{http_code}' "${REGISTER_URL}/health")
    server_body=$(curl -s "${REGISTER_URL}/health")
    [[ "$server_status" == "200" ]]
    [[ "$server_body" == *"healthy"* ]]

    # Frontend nginx
    local frontend_status frontend_body
    frontend_status=$(curl -s -o /dev/null -w '%{http_code}' "${FRONTEND_URL}/")
    frontend_body=$(curl -s "${FRONTEND_URL}/")
    [[ "$frontend_status" == "200" ]]
    [[ "$frontend_body" == *"<html"* ]] || [[ "$frontend_body" == *"<!doctype"* ]] || [[ "$frontend_body" == *"<!DOCTYPE"* ]]
}

# ============================================================================
# E2E persistence: nginx → server → Irmin
# ============================================================================

# --------------------------------------------------------------------------
# irmin_meta_path TREE_ID — resolve the workspace-scoped meta path for a tree.
# The server stores metadata at workspaces/{wsId}/risk-trees/{treeId}/meta;
# the HTTP API never exposes wsId, so locate the path by listing the store.
# --------------------------------------------------------------------------
irmin_meta_path() {
    local tree_id="$1"
    curl -s -X POST "${IRMIN_DIRECT_URL}/graphql" \
        -H 'Content-Type: application/json' \
        -d '{"query":"{ main { tree { get_tree(path: \"workspaces\") { list_contents_recursively { path } } } } }"}' \
      | jq -r --arg t "$tree_id" \
          '.data.main.tree.get_tree.list_contents_recursively[].path
           | select(endswith("/risk-trees/" + $t + "/meta"))' \
      | head -1 | sed 's|^/||'
}

irmin_get() {
    local path="$1"
    curl -s -X POST "${IRMIN_DIRECT_URL}/graphql" \
        -H 'Content-Type: application/json' \
        -d "{\"query\":\"{ main { tree { get(path: \\\"${path}\\\") } } }\"}" \
      | jq -r '.data.main.tree.get'
}

@test "A02: workspace created via nginx is persisted to Irmin" {
    # Create workspace through the frontend (nginx) entry point.
    create_workspace "${FRONTEND_URL}"

    # Verify the tree landed in Irmin by querying Irmin directly.
    local meta_path meta_value
    meta_path=$(irmin_meta_path "$TREE_ID")
    [[ -n "$meta_path" ]]

    meta_value=$(irmin_get "$meta_path")
    [[ "$meta_value" != "null" ]]
    [[ -n "$meta_value" ]]
}

@test "A03: tree metadata in Irmin has expected structure" {
    create_workspace "${FRONTEND_URL}"

    # Read the raw metadata JSON from Irmin
    local meta_path meta_json
    meta_path=$(irmin_meta_path "$TREE_ID")
    [[ -n "$meta_path" ]]
    meta_json=$(irmin_get "$meta_path")

    # The stored value is a JSON string — parse it and check fields
    local id name rootId seedVarHighWater
    id=$(echo "$meta_json" | jq -r '.id')
    name=$(echo "$meta_json" | jq -r '.name')
    rootId=$(echo "$meta_json" | jq -r '.rootId')
    seedVarHighWater=$(echo "$meta_json" | jq -r '.seedVarHighWater')

    [[ "$id" == "$TREE_ID" ]]
    [[ -n "$name" ]]
    [[ "$name" != "null" ]]
    [[ -n "$rootId" ]]
    [[ "$rootId" != "null" ]]
    # Seed identity watermark (PLAN-SEED-IDENTITY): present and positive
    [[ "$seedVarHighWater" =~ ^[0-9]+$ ]]
    [[ "$seedVarHighWater" -ge 1 ]]
}

@test "A04: list risk-trees via nginx returns Irmin-persisted data" {
    create_workspace "${FRONTEND_URL}"

    # List trees through the full stack: nginx → server → Irmin → response
    local response count
    response=$(curl -s -H 'Accept: application/json' -H 'X-Branch: main' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees")
    count=$(echo "$response" | jq 'length')
    [[ "$count" -ge 1 ]]

    # The tree ID in the API response should match what we created
    local first_id
    first_id=$(echo "$response" | jq -r '.[0].id')
    [[ "$first_id" == "$TREE_ID" ]]
}

@test "A05: get tree by ID via nginx returns Irmin-persisted tree" {
    create_workspace "${FRONTEND_URL}"

    # Fetch a specific tree through the full stack
    local status response tree_id tree_name
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -H 'Accept: application/json' -H 'X-Branch: main' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees/${TREE_ID}")
    response=$(curl -s -H 'Accept: application/json' -H 'X-Branch: main' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees/${TREE_ID}")

    [[ "$status" == "200" ]]

    tree_id=$(echo "$response" | jq -r '.id')
    tree_name=$(echo "$response" | jq -r '.name')
    [[ "$tree_id" == "$TREE_ID" ]]
    [[ -n "$tree_name" ]]
    [[ "$tree_name" != "null" ]]
}

# ============================================================================
# Example staging scripts — curl and HTTPie variants must stay in step
# ============================================================================
# These scripts build a workspace whose tree carries a known commit history,
# which the Analyze history slider needs. They live here rather than in the
# in-memory suite because commit history requires the Irmin backend — the
# in-memory repository keeps none.
#
# Unlike the demo scripts they issue no queries, so there is no evaluated-query
# count to assert. What they do print is a summary naming exactly what they
# built, and the markers below are that summary. Both client variants are
# checked against the same markers, so the two examples cannot drift apart or
# fall behind an API change without a test going red.

run_staging_script() {
    local script="$1"
    local path="${BATS_TEST_DIRNAME}/../../examples/${script}"
    [[ -f "$path" ]] || { echo "missing script: $path" >&2; return 1; }

    local output
    output=$(bash "$path" "${FRONTEND_URL}" 2>&1) || {
        echo "$output" >&2
        return 1
    }

    local marker
    for marker in \
        "Workspace key" \
        "Tree ID" \
        "Branch main   : 6 commits" \
        "Branch mitig. : + M1, M2 on top of the fork" \
        "pre-insider-audit : forked from commit C2" \
        "Open in app"
    do
        if ! echo "$output" | grep -q "$marker"; then
            echo "expected summary line missing: ${marker}" >&2
            echo "$output" >&2
            return 1
        fi
    done

    # A jq parse error, or a null where an id or key belongs, means a response
    # was not the JSON the script expected.
    if echo "$output" | grep -qE 'parse error|Invalid value for|: null'; then
        echo "$output" >&2
        return 1
    fi
}

@test "A06: examples/stage-history-slider-curl.sh stages the full commit history" {
    run_staging_script stage-history-slider-curl.sh
}

@test "A07: examples/stage-history-slider-httpie.sh stages the full commit history" {
    require_httpie
    run_staging_script stage-history-slider-httpie.sh
}
