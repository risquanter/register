#!/usr/bin/env bats
# ============================================================================
# Suite A — End-to-End: Frontend → Server → Irmin
# ============================================================================
# Compose: local/frontend:dev + register-server:prod + local/irmin-prod:3.11
# Profile: --profile persistence --profile frontend
# Purpose: True end-to-end validation. The server is configured with
#          REGISTER_REPOSITORY_TYPE=irmin so workspace data flows through
#          nginx → register-server → Irmin. Tests verify that data created
#          via the public API is actually persisted in Irmin by querying
#          Irmin's GraphQL endpoint directly.
#
# Run:     bats tests/bats/suite-a-full-prod.bats
# Prereq:  Images built: register-server:prod, local/frontend:dev,
#          local/irmin-prod:3.11 (and local/irmin-builder:3.11)
# ============================================================================

# Irmin host port — matches docker-compose.yml: "9080:8080"
IRMIN_DIRECT_PORT="9080"
IRMIN_DIRECT_URL="http://localhost:${IRMIN_DIRECT_PORT}"

setup_file() {
    # Wire the server to Irmin for real persistence (default is in-memory).
    export REGISTER_REPOSITORY_TYPE=irmin
    # Compose-internal URL: the server container resolves 'irmin' via Docker DNS.
    export IRMIN_URL="http://irmin:8080"

    # Relax workspace-creation rate limit for test runs (prod default: 5/hour).
    export REGISTER_WORKSPACE_MAX_CREATES_PER_IP=100

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

@test "A02: workspace created via nginx is persisted to Irmin" {
    # Create workspace through the frontend (nginx) entry point.
    create_workspace "${FRONTEND_URL}"

    # Verify the tree landed in Irmin by querying Irmin directly.
    # The server stores tree metadata at: risk-trees/{treeId}/meta
    local irmin_meta
    irmin_meta=$(curl -s -X POST "${IRMIN_DIRECT_URL}/graphql" \
        -H 'Content-Type: application/json' \
        -d "{\"query\":\"{ main { tree { get(path: \\\"risk-trees/${TREE_ID}/meta\\\") } } }\"}")

    # get returns a non-null scalar string if the path exists
    local meta_value
    meta_value=$(echo "$irmin_meta" | jq -r '.data.main.tree.get')
    [[ "$meta_value" != "null" ]]
    [[ -n "$meta_value" ]]
}

@test "A03: tree metadata in Irmin has expected structure" {
    create_workspace "${FRONTEND_URL}"

    # Read the raw metadata JSON from Irmin
    local irmin_meta meta_json
    irmin_meta=$(curl -s -X POST "${IRMIN_DIRECT_URL}/graphql" \
        -H 'Content-Type: application/json' \
        -d "{\"query\":\"{ main { tree { get(path: \\\"risk-trees/${TREE_ID}/meta\\\") } } }\"}")
    meta_json=$(echo "$irmin_meta" | jq -r '.data.main.tree.get')

    # The stored value is a JSON string — parse it and check fields
    local id name rootId
    id=$(echo "$meta_json" | jq -r '.id')
    name=$(echo "$meta_json" | jq -r '.name')
    rootId=$(echo "$meta_json" | jq -r '.rootId')

    [[ "$id" == "$TREE_ID" ]]
    [[ -n "$name" ]]
    [[ "$name" != "null" ]]
    [[ -n "$rootId" ]]
    [[ "$rootId" != "null" ]]
}

@test "A04: list risk-trees via nginx returns Irmin-persisted data" {
    create_workspace "${FRONTEND_URL}"

    # List trees through the full stack: nginx → server → Irmin → response
    local response count
    response=$(curl -s -H 'Accept: application/json' \
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
        -H 'Accept: application/json' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees/${TREE_ID}")
    response=$(curl -s -H 'Accept: application/json' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees/${TREE_ID}")

    [[ "$status" == "200" ]]

    tree_id=$(echo "$response" | jq -r '.id')
    tree_name=$(echo "$response" | jq -r '.name')
    [[ "$tree_id" == "$TREE_ID" ]]
    [[ -n "$tree_name" ]]
    [[ "$tree_name" != "null" ]]
}
