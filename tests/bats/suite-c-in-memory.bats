#!/usr/bin/env bats
# ============================================================================
# Suite C — In-Memory Mode (No Irmin Dependency)
# ============================================================================
# Compose: register-server:prod (in-memory) + local/frontend:dev (nginx)
# Purpose: Primary ADR-INFRA-007 / ADR-027 validation suite.
#          Tests the full nginx routing behaviour and register-server in-memory
#          mode without Irmin complexity.
#
# Run:     bats tests/bats/suite-c-in-memory.bats
# Prereq:  Images built: register-server:prod, local/frontend:dev
# ============================================================================

setup_file() {
    # Relax workspace-creation rate limit for test runs (prod default: 5/hour).
    # The HOCON config has: maxCreatesPerIpPerHour = ${?REGISTER_WORKSPACE_MAX_CREATES_PER_IP}
    export REGISTER_WORKSPACE_MAX_CREATES_PER_IP=100

    # Start register-server + frontend (no persistence profile → in-memory mode)
    docker compose --profile frontend up -d --wait 2>&1 || {
        echo "Failed to start compose services" >&2
        docker compose --profile frontend logs 2>&1
        return 1
    }

    # Load helpers
    load helpers/setup

    # Wait for services to be ready
    wait_for_url "${REGISTER_URL}/health" 30
    wait_for_url "${FRONTEND_URL}/" 30
}

teardown_file() {
    docker compose --profile frontend down 2>&1
}

setup() {
    load helpers/setup
}

# ============================================================================
# Register-server health & basic API
# ============================================================================

@test "C01: register-server /health returns 200" {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' "${REGISTER_URL}/health")
    [[ "$status" == "200" ]]
}

@test "C02: POST /workspaces creates workspace with key" {
    create_workspace
    [[ -n "$WORKSPACE_KEY" ]]
    [[ "$WORKSPACE_KEY" != "null" ]]
    [[ -n "$TREE_ID" ]]
    [[ "$TREE_ID" != "null" ]]
}

@test "C03: GET /w/{key}/risk-trees returns JSON array" {
    create_workspace
    local response
    response=$(curl -s -H 'Accept: application/json' \
        "${REGISTER_URL}/w/${WORKSPACE_KEY}/risk-trees")
    # Response should be a JSON array with at least the bootstrap tree
    local count
    count=$(echo "$response" | jq 'length')
    [[ "$count" -ge 1 ]]
}

@test "C04: GET /risk-trees (list-all) returns 403 when gate disabled (default)" {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' "${REGISTER_URL}/risk-trees")
    [[ "$status" == "403" ]]
}

# ============================================================================
# nginx — SPA serving
# ============================================================================

@test "C05: nginx serves index.html at /" {
    local response
    response=$(curl -s "${FRONTEND_URL}/")
    [[ "$response" == *"<html"* ]] || [[ "$response" == *"<!doctype"* ]] || [[ "$response" == *"<!DOCTYPE"* ]]
}

@test "C06: nginx SPA fallback — GET /nonexistent returns HTML (not 404)" {
    local status body
    status=$(curl -s -o /dev/null -w '%{http_code}' "${FRONTEND_URL}/nonexistent/path")
    body=$(curl -s "${FRONTEND_URL}/nonexistent/path")
    [[ "$status" == "200" ]]
    [[ "$body" == *"<html"* ]] || [[ "$body" == *"<!doctype"* ]] || [[ "$body" == *"<!DOCTYPE"* ]]
}

@test "C07: server_tokens off — no nginx version in Server header" {
    local server_header
    server_header=$(curl -sI "${FRONTEND_URL}/" | grep -i '^server:' | tr -d '\r')
    # Should be "Server: nginx" without version, not "Server: nginx/1.27.5"
    [[ "$server_header" == *"nginx"* ]]
    [[ "$server_header" != *"nginx/"* ]]
}

@test "C08: X-Content-Type-Options: nosniff on SPA fallback" {
    local header
    header=$(curl -sI "${FRONTEND_URL}/" | grep -i '^x-content-type-options:' | tr -d '\r')
    [[ "$header" == *"nosniff"* ]]
}

# ============================================================================
# nginx — Accept-header discrimination (ADR-INFRA-007 §1 core feature)
# ============================================================================

@test "C09: /w/{key} + Accept: text/html returns HTML (SPA shell)" {
    create_workspace
    local status body
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -H 'Accept: text/html' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}")
    body=$(curl -s -H 'Accept: text/html' "${FRONTEND_URL}/w/${WORKSPACE_KEY}")
    [[ "$status" == "200" ]]
    [[ "$body" == *"<html"* ]] || [[ "$body" == *"<!doctype"* ]] || [[ "$body" == *"<!DOCTYPE"* ]]
}

@test "C10: /w/{key}/risk-trees + Accept: application/json proxies to backend" {
    create_workspace
    local response status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -H 'Accept: application/json' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees")
    response=$(curl -s -H 'Accept: application/json' \
        "${FRONTEND_URL}/w/${WORKSPACE_KEY}/risk-trees")
    [[ "$status" == "200" ]]
    # Should be a JSON array from the real backend, not HTML from SPA fallback
    local count
    count=$(echo "$response" | jq 'length')
    [[ "$count" -ge 1 ]]
}

# ============================================================================
# nginx — API proxying
# ============================================================================

@test "C11: /health via nginx proxies to backend" {
    local status body
    status=$(curl -s -o /dev/null -w '%{http_code}' "${FRONTEND_URL}/health")
    body=$(curl -s "${FRONTEND_URL}/health")
    [[ "$status" == "200" ]]
    [[ "$body" == *"healthy"* ]]
}

@test "C12: /docs via nginx proxies to backend" {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' "${FRONTEND_URL}/docs")
    # Tapir Swagger: 308 redirect to /docs/docs.yaml, or 200/3xx depending on version
    [[ "$status" == "200" ]] || [[ "$status" == "301" ]] || [[ "$status" == "303" ]] || [[ "$status" == "308" ]]
}

@test "C13: POST /workspaces via nginx proxies to backend" {
    create_workspace "${FRONTEND_URL}"
    [[ -n "$WORKSPACE_KEY" ]]
    [[ "$WORKSPACE_KEY" != "null" ]]
}

# ============================================================================
# nginx — Static asset caching
# ============================================================================

@test "C14: static .js asset has Cache-Control immutable header" {
    # Find a hashed JS file from the SPA build output.
    # Vite outputs: <script type="module" crossorigin src="/assets/index-XXX.js">
    #
    # The sed extracts the JS path from the first <script src="..."> tag:
    #   -n            suppress default output (only print explicit /p matches)
    #   s/            begin substitution
    #     .*src="     match everything up to and including src="
    #     \([^"]*\.js\)  capture group: any non-quote chars ending in .js
    #     ".*         match the closing quote and rest of line
    #   /\1/p        replace entire line with the capture group, print it
    #
    # Equivalent with GNU grep (not available in BusyBox/Alpine):
    #   grep -oP '(?<=src=")[^"]*\.js'
    local js_path
    js_path=$(curl -s "${FRONTEND_URL}/" | sed -n 's/.*src="\([^"]*\.js\)".*/\1/p' | head -1)

    if [[ -z "$js_path" ]]; then
        skip "No .js file found in index.html — build may have no hashed assets"
    fi

    local cache_header
    cache_header=$(curl -sI "${FRONTEND_URL}${js_path}" | grep -i '^cache-control:' | tr -d '\r')
    [[ "$cache_header" == *"immutable"* ]]
    [[ "$cache_header" == *"max-age=31536000"* ]]
}

@test "C15: static .js asset has X-Content-Type-Options: nosniff" {
    # Extract hashed JS path from index.html — see C14 for sed pattern explanation.
    local js_path
    js_path=$(curl -s "${FRONTEND_URL}/" | sed -n 's/.*src="\([^"]*\.js\)".*/\1/p' | head -1)

    if [[ -z "$js_path" ]]; then
        skip "No .js file found in index.html"
    fi

    local header
    header=$(curl -sI "${FRONTEND_URL}${js_path}" | grep -i '^x-content-type-options:' | tr -d '\r')
    [[ "$header" == *"nosniff"* ]]
}

# ============================================================================
# Full CRUD round-trip (in-memory)
# ============================================================================

@test "C16: full round-trip — create workspace, list trees, get tree by id" {
    create_workspace
    
    # List trees via workspace key
    local list_response
    list_response=$(curl -s -H 'Accept: application/json' \
        "${REGISTER_URL}/w/${WORKSPACE_KEY}/risk-trees")
    local count
    count=$(echo "$list_response" | jq 'length')
    [[ "$count" -ge 1 ]]

    # Get specific tree by ID
    local tree_response status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        "${REGISTER_URL}/w/${WORKSPACE_KEY}/risk-trees/${TREE_ID}")
    [[ "$status" == "200" ]]
}
