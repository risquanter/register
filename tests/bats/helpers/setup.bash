#!/usr/bin/env bash
# ============================================================================
# Shared BATS test helpers
# ============================================================================
# Source this from setup() in each .bats file:
#   load helpers/setup
#
# Provides:
#   - Common port/URL variables
#   - wait_for_url()      — poll GET until an endpoint is healthy
#   - wait_for_graphql()  — poll POST with introspection query until 200
#   - create_workspace()  — bootstrap workspace, set WORKSPACE_KEY + TREE_ID
# ============================================================================

# --------------------------------------------------------------------------
# Port configuration — matches docker-compose.yml host port mappings.
# Override with env vars for custom setups.
# --------------------------------------------------------------------------
export REGISTER_PORT="${REGISTER_PORT:-8090}"
export REGISTER_HEALTH_PORT="${REGISTER_HEALTH_PORT:-8091}"
export FRONTEND_PORT="${FRONTEND_PORT:-18080}"
export IRMIN_PORT="${IRMIN_PORT:-9080}"

export REGISTER_URL="http://localhost:${REGISTER_PORT}"
export FRONTEND_URL="http://localhost:${FRONTEND_PORT}"
export IRMIN_URL="http://localhost:${IRMIN_PORT}"

# --------------------------------------------------------------------------
# wait_for_url URL [MAX_SECONDS] [EXPECTED_STATUS]
#   Poll GET URL until HTTP status matches, or fail after timeout.
# --------------------------------------------------------------------------
wait_for_url() {
    local url="$1"
    local max_seconds="${2:-30}"
    local expected_status="${3:-200}"
    local elapsed=0

    while [[ $elapsed -lt $max_seconds ]]; do
        local status
        status=$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null) || true
        if [[ "$status" == "$expected_status" ]]; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "Timed out waiting for $url (expected $expected_status, last: $status)" >&2
    return 1
}

# --------------------------------------------------------------------------
# wait_for_graphql URL [MAX_SECONDS]
#   Poll a GraphQL endpoint with a POST introspection query until 200.
# --------------------------------------------------------------------------
wait_for_graphql() {
    local url="$1"
    local max_seconds="${2:-30}"
    local elapsed=0

    while [[ $elapsed -lt $max_seconds ]]; do
        local status
        status=$(curl -s -o /dev/null -w '%{http_code}' \
            -X POST "$url" \
            -H 'Content-Type: application/json' \
            -d '{"query":"{ __typename }"}' 2>/dev/null) || true
        if [[ "$status" == "200" ]]; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "Timed out waiting for GraphQL at $url (last: $status)" >&2
    return 1
}

# --------------------------------------------------------------------------
# create_workspace [BACKEND_URL]
#   POST /workspaces with a minimal lognormal tree.
#   Sets global: WORKSPACE_KEY, TREE_ID, BOOTSTRAP_RESPONSE
# --------------------------------------------------------------------------
create_workspace() {
    local base_url="${1:-$REGISTER_URL}"

    # Unique name per invocation to avoid DUPLICATE_VALUE validation error.
    local unique_name="BATS-${BATS_TEST_NUMBER:-0}-$(date +%s%N)"

    local payload
    payload=$(cat <<EOF
{
    "name": "${unique_name}",
    "portfolios": [{"name": "Root", "parentName": null}],
    "leaves": [{
        "name": "Test Risk",
        "parentName": "Root",
        "distributionType": "lognormal",
        "probability": 0.1,
        "minLoss": 1000,
        "maxLoss": 50000,
        "percentiles": null,
        "quantiles": null
    }]
}
EOF
    )

    BOOTSTRAP_RESPONSE=$(curl -s -X POST "${base_url}/workspaces" \
        -H 'Content-Type: application/json' \
        -d "$payload")

    WORKSPACE_KEY=$(echo "$BOOTSTRAP_RESPONSE" | jq -r '.workspaceKey')
    TREE_ID=$(echo "$BOOTSTRAP_RESPONSE" | jq -r '.tree.id')

    if [[ -z "$WORKSPACE_KEY" || "$WORKSPACE_KEY" == "null" ]]; then
        echo "Failed to create workspace: $BOOTSTRAP_RESPONSE" >&2
        return 1
    fi
}
