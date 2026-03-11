#!/usr/bin/env bats
# ============================================================================
# Suite B — Irmin Production Image (Standalone)
# ============================================================================
# Container: local/irmin-prod:3.11 (standalone, no compose)
# Purpose:   Validate the production Irmin image: health, GraphQL round-trip,
#            non-root enforcement (UID 65532), read-only root filesystem.
#
# Uses irmin-prod (not irmin-dev) because the security properties under test
# (UID 65532, no shell toolchain, minimal attack surface) only exist in the
# prod image. The dev image runs as the 'opam' user with a full toolchain —
# useful for interactive debugging but not representative of production.
#
# Run:       bats tests/bats/suite-b-irmin-prod.bats
# Prereq:    Image built: local/irmin-prod:3.11
#            (requires local/irmin-builder:3.11 — see Dockerfile.irmin-builder)
# ============================================================================

IRMIN_CONTAINER="irmin-prod-bats-test"
IRMIN_PORT="19080"

setup_file() {
    # Start a standalone irmin-prod container (not compose-managed)
    docker rm -f "$IRMIN_CONTAINER" 2>/dev/null || true
    docker run -d \
        --name "$IRMIN_CONTAINER" \
        --read-only \
        --tmpfs /tmp:size=10M \
        -p "${IRMIN_PORT}:8080" \
        -v irmin-bats-data:/data \
        local/irmin-prod:3.11

    # Wait for GraphQL endpoint
    local elapsed=0
    while [[ $elapsed -lt 30 ]]; do
        local status
        status=$(curl -s -o /dev/null -w '%{http_code}' \
            -X POST "http://localhost:${IRMIN_PORT}/graphql" \
            -H 'Content-Type: application/json' \
            -d '{"query":"{ __typename }"}' 2>/dev/null) || true
        if [[ "$status" == "200" ]]; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo "Irmin container did not become healthy" >&2
    docker logs "$IRMIN_CONTAINER" 2>&1
    return 1
}

teardown_file() {
    docker rm -f "$IRMIN_CONTAINER" 2>/dev/null || true
    docker volume rm irmin-bats-data 2>/dev/null || true
}

# ============================================================================
# Health & GraphQL
# ============================================================================

@test "B01: container starts and responds to GraphQL" {
    local status body
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X POST "http://localhost:${IRMIN_PORT}/graphql" \
        -H 'Content-Type: application/json' \
        -d '{"query":"{ __typename }"}')
    [[ "$status" == "200" ]]
}

@test "B02: GraphQL introspection works" {
    local response
    response=$(curl -s -X POST "http://localhost:${IRMIN_PORT}/graphql" \
        -H 'Content-Type: application/json' \
        -d '{"query":"{ __schema { types { name } } }"}')
    # Should contain standard GraphQL types
    echo "$response" | jq -e '.data.__schema.types' > /dev/null
}

@test "B03: set/get round-trip via GraphQL" {
    # Set a value
    local set_response
    set_response=$(curl -s -X POST "http://localhost:${IRMIN_PORT}/graphql" \
        -H 'Content-Type: application/json' \
        -d '{
            "query": "mutation { set(path: \"/bats-test/key1\", value: \"hello-from-bats\") { hash } }"
        }')
    echo "$set_response" | jq -e '.data.set.hash' > /dev/null

    # Get the value back.
    # Irmin get returns a scalar string — no { value } wrapper.
    local get_response value
    get_response=$(curl -s -X POST "http://localhost:${IRMIN_PORT}/graphql" \
        -H 'Content-Type: application/json' \
        -d '{
            "query": "{ main { tree { get(path: \"/bats-test/key1\") } } }"
        }')
    value=$(echo "$get_response" | jq -r '.data.main.tree.get')
    [[ "$value" == "hello-from-bats" ]]
}

# ============================================================================
# Security properties
# ============================================================================

@test "B04: container runs as non-root (UID 65532)" {
    local uid
    uid=$(docker exec "$IRMIN_CONTAINER" id -u)
    [[ "$uid" == "65532" ]]
}

@test "B05: read-only root filesystem — write to / fails" {
    # Attempt to write outside allowed paths — should fail
    run docker exec "$IRMIN_CONTAINER" touch /test-readonly
    [[ "$status" -ne 0 ]]
}
