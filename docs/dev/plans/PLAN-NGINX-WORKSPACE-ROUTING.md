# Plan: route each workspace to one server instance, and stop logging the workspace key

**Status:** Ruled, awaiting approval as the session's governing plan.
**Date:** 2026-09-13.
**ADR reference:** ADR-021 (capability URLs — the workspace key is the
credential); ADR-022 (secret handling — R3, redacted `toString`); ADR-036
(confidential internal identifiers — what may appear in logs); ADR-027 and
ADR-INFRA-007 (nginx serving and routing); ADR-031 (the principle that a process
must not hard-fail because a dependency is still booting).

Two changes to one file, sharing one mechanism. Both are infrastructure
configuration; no Scala source changes, no wire change, no API change.

---

## Objective

The frontend container runs nginx in front of the register server. Two defects
live in its configuration, and one mechanism fixes both.

**The workspace key is written to the access log in plaintext.** The nginx
configuration sets `access_log /dev/stdout;` with no format argument, which
selects nginx's default `combined` format. That format includes `$request` — the
full request line, method, URI and protocol. The workspace key is the first path
segment of every workspace route (`/w/{key}/...`), so every API request writes
the capability credential to standard output, which Docker and Kubernetes
collect into log aggregation and retain.

**All workspaces are spread across all server instances.** The current
configuration proxies with a variable (`set $backend "__BACKEND__"; proxy_pass
$backend;`), which defers DNS resolution to request time. When the backend name
resolves to several addresses, requests are distributed across them with no
regard to which workspace they belong to. Several pieces of per-workspace state
live inside a single server process and are therefore fragmented by that
distribution, and one of them is not merely inefficient but incorrect.

---

## Background — why each defect matters

### Why logging the key is a credential leak, not a shape concern

Read this together with ADR-021 and ADR-022, because the gap is precisely
between what they cover and what they do not.

A *capability URL* is a URL that carries its own authorization: possessing the
URL is what grants access, with no separate login. `WorkspaceKeySecret` is that
credential here — 128 bits from `SecureRandom`, base64url-encoded to 22
characters, placed in the path so a workspace can be shared by sharing a link.

ADR-022 hardens the type against leaking. Rule R3 requires a redacted
`toString`, so `println(key)` or string interpolation prints `TypeName(***)` and
never the value; R4 requires an explicit `reveal` method so every extraction is
visible in review. Those rules work, and the server itself does not log request
URIs at all — searched and confirmed.

**The protection is entirely inside the application, and nginx is in front of
it.** nginx has no knowledge of Scala types; it logs the raw request line it
received. So a credential that the type system is carefully built to keep out of
logs is written to logs anyway, one line per request, by a component the type
system cannot reach.

ADR-021 §4 already enumerates the leakage channels it closes for this
credential: HTTPS-only against wire sniffing, `Referrer-Policy: no-referrer`
against the referer header, and `Cache-Control: no-store` against proxy caching.
**Access logs are not in that list.** This is a gap in a threat model that was
already reasoning about exactly this class of exposure, not a newly invented
concern.

### Why the rest of the log line is not a leak — the verification

The ruling on this change was conditional on verifying that masking only the key
is enough, and that what remains is safe to keep. It is, and the authority is
our own accepted decision record rather than a judgement made here.

ADR-036 governs identifiers that scope data without granting access —
`WorkspaceId`, and by extension `TreeId` and `NodeId`. Its §3, "Lighter Than a
Credential", states the rule directly:

> A confidential identifier **may appear in server logs**, internal storage
> paths, and merge commit messages. The reasoning that closes it at the boundary
> is enumeration-oracle / BOLA (Broken Object-Level Authorization), not
> secret-leakage.

So the method, the path shape, the tree and node identifiers within it, the
status code, the byte count and the timing are all explicitly permitted in logs
by an accepted decision record. Only the workspace key is a credential, and only
the workspace key is removed. Everything operators use logs for — which endpoint
was hit, whether it succeeded, how long it took — is preserved exactly.

Two facts complete the verification:

- **nginx is the only component that logs the key.** The register server does
  not log request URIs; searched across the HTTP layer and confirmed. So masking
  at nginx is complete, with no second site to fix.
- **The credential is unchanged by masking.** Masking affects the log line only.
  The key still reaches the backend in the request, which is how authorization
  works.

### How urgent the log leak is

The credential is live and the exposure is bounded; both halves matter for
deciding whether this can wait for a planned change.

**The application side is clean, and was checked rather than assumed.**
`WorkspaceKeySecret` has a redacted `toString` — its specification asserts that
the value prints as `WorkspaceKeySecret(***)` and that the raw string does not
appear. No logging statement in the server interpolates a key, the server logs
no request URIs, and zio-http is configured with no request-logging middleware.
There is no second leak to find in Scala.

**The nginx side writes live credentials on every request**, to standard output,
which the container runtime collects and a log pipeline would retain.

**The exposure window is bounded by workspace expiry, not by log retention.**
The defaults are a 72-hour absolute lifetime and a 1-hour idle timeout. A key
found in a log is therefore useful only within an hour of the workspace's last
legitimate use, and for at most 72 hours in total. A key in a month-old log is
dead. This is a genuine limit, not a mitigation offered to minimise the finding —
but note that it disappears in the configuration where `ttl` and `idleTimeout`
are both zero, which switches the reaper off entirely and makes keys permanent.

**What follows.** With logs going to a local Docker daemon and no real user data,
this is a defect to fix on the normal path rather than an incident. **Two
conditions flip that, and either one makes it urgent enough to land ahead of
everything else:** logs being shipped to any aggregation or retention system
beyond the local daemon, or a deployment holding data that is not test data.
That is why this plan is first in the landing order despite having no technical
dependency forcing it there.

### Why per-workspace routing matters — and where it is a correctness break

Four pieces of state live per workspace inside one server process.

`ContentCache` holds simulation results per workspace, handed out by
`ContentCacheRegistry`. `MitigationScopeResolver` holds resolved mitigation scopes per
workspace, handed out by `MitigationScopeResolverRegistry`. Spreading one workspace's
requests across instances means each instance separately rebuilds both. That is
waste, not incorrectness — a cache miss produces a correct answer slowly.

`SSEHub` is different, and it is the reason this change is not merely an
optimization.

**Server-Sent Events is a one-way channel over which the server pushes updates
to an open browser connection.** The server half is fully built and live:
`SSEHub` broadcasts per tree, `SSEEndpoints` serves
`GET /w/{key}/events/tree/{treeId}` as `text/event-stream`, `SSEController`
authorizes and attaches a 30-second heartbeat, and `InvalidationHandler`
publishes on every mutation from `RiskTreeServiceLive`. All of it is wired into
the application's layer graph. The browser half does not exist — nothing in the
frontend opens the connection — so nothing subscribes today.

`SSEHub` keeps its subscribers in a plain map inside one process. Publishing
reaches only subscribers registered in that same process. With requests spread
across instances:

1. A browser opens the events connection; the proxy sends it to instance 1,
   which registers the subscriber.
2. The same user edits a node. That is a separate request, routed
   independently — to instance 2.
3. Instance 2 performs the update and publishes into **its own** hub, which has
   no subscribers for that tree. It logs `"no subscribers"` at debug level and
   returns zero.
4. The connection stays open and heartbeats keep arriving, so it looks healthy.
   The update never arrives.

The failure is silent in both directions: no client error, no server warning,
and a heartbeat actively signalling health. It is latent today because nothing
subscribes, and the change most likely to activate it — adding the browser
half — is a frontend change whose author has no reason to look at proxy
configuration.

Routing every request for one workspace to one instance fixes this
structurally. It works because the workspace key is the first path segment of
**both** the events route and every mutation route, so both hash to the same
instance. `SSEHub` is keyed by tree rather than workspace, but a tree belongs to
exactly one workspace, so workspace affinity implies tree affinity.

---

## The mechanism — one extracted key, two consumers

Both changes need the same thing: the workspace key, pulled out of the request
path as a variable. nginx's `map` directive does that.

```nginx
    # Workspace key — first path segment after /w/
    map $uri $ws_key {
        "~^/w/(?<k>[^/]+)"  $k;
        default             "nokey";
    }
```

One consumer turns it into a backend choice, the other into a masked path.

### Consumer 1 — deterministic instance selection

```nginx
    split_clients "$ws_key" $ws_instance {
        50%  "register-server-1";
        *    "register-server-2";
    }
```

`split_clients` hashes its input and maps the hash into fixed percentage
buckets. The same key always produces the same bucket, which is exactly the
affinity required.

**Why `split_clients` rather than an `upstream` block with `hash $ws_key
consistent`.** The obvious way to hash-route in nginx is a `hash` directive
inside an `upstream` block. It was tested and it is the wrong tool here, for a
reason that showed up immediately:

```
nginx: [emerg] host not found in upstream "register-server-1:8090"
nginx: configuration file /tmp/t.conf test failed
```

An `upstream` block resolves its server names when the configuration is loaded,
and **nginx refuses to start if any of them does not resolve**. That directly
destroys a property the current configuration was deliberately built to have —
the Dockerfile states it plainly: *"proxy_pass uses variables (set $backend ...)
so nginx defers DNS lookup to request time and does NOT fail at startup if the
backend is unavailable."* It is also the failure mode ADR-031 exists to prevent:
a process that hard-fails because a dependency is still booting turns an
ordering race into a crash loop.

`split_clients` produces a variable, so `proxy_pass` stays variable-based and
startup behaviour is unchanged. Verified: the configuration below passes
`nginx -t` and serves requests with neither backend name resolvable.

**The cost, stated plainly.** `split_clients` is not consistent hashing.
Consistent hashing moves roughly one key in N when the instance count changes;
percentage buckets reshuffle most keys. Both require a configuration change and
a reload to change the instance count, so the difference appears only at a scale
event, and its consequence is that open events connections drop and browsers
reconnect. Given that dynamic scaling is explicitly out of scope, keeping
startup resilience is worth more than minimal remapping. **This is the one
judgement inside a ruled decision; it is stated here so it can be overruled
rather than discovered later.**

### The comment that ships with the routing block

The reasoning for choosing `split_clients` is not visible from the directive. A
reader who knows nginx will reach for an upstream hash and needs to know why it
is absent, so the rejected alternative is recorded where the decision lives.
This exact block goes immediately above `split_clients` in the template:

```nginx
    # Workspace affinity: every request for one workspace reaches one instance.
    # Required by Server-Sent Events, whose subscriber map is per process, and
    # relied on by the per-workspace simulation and mitigation-scope caches.
    #
    # split_clients, not "upstream { hash $ws_key consistent; }": an upstream
    # block resolves its server names when the config loads and nginx refuses to
    # start if one does not resolve, which would lose the deferred-DNS start-up
    # behaviour the variable proxy_pass below exists to provide. split_clients
    # yields a variable, so resolution stays at request time.
    #
    # The trade this accepts: percentage buckets are not consistent hashing, so
    # changing the instance count reshuffles most workspaces rather than one in
    # N. Both forms need a config change and a reload to resize, so the
    # difference appears only at a scale event, and its cost is that open event
    # streams drop and reconnect.
    #
    # Generated from BACKEND_INSTANCES by the entrypoint; a single instance
    # produces one catch-all bucket.
```

Written as current state, with no plan reference, no date and no decision label,
per the comment rule. It survives the plan's completion because it explains the
code as it stands rather than recording how it got there.

### Consumer 2 — the masked access log

```nginx
    map $uri $uri_masked {
        "~^/w/[^/]+(?<rest>/.*)?$"  "/w/***$rest";
        default                      $uri;
    }

    log_format masked '$remote_addr - [$time_local] "$request_method $uri_masked $server_protocol" '
                      '$status $body_bytes_sent $request_time "$http_user_agent"';

    access_log /dev/stdout masked;
```

`$uri_masked` is used in place of `$request`, because `$request` contains the
raw request line and cannot be masked. The format keeps the method, the masked
path, the protocol, the status, the response size, the request duration and the
user agent. `$request_time` is an addition — the default `combined` format does
not include it, and it is the field operators most often want.

A workspace request logs as:

```
172.18.0.1 - [13/Sep/2026:09:14:22 +0000] "GET /w/***/trees/01JB.../analysis HTTP/1.1" 200 4213 0.043 "Mozilla/5.0 ..."
```

The tree identifier is still there, per ADR-036 §3. The credential is not.

### Verified behaviour

Run against `local/frontend:0.10.30` with both backend names unresolvable:

- `nginx -t` reports the configuration syntax is ok and the test is successful.
- nginx starts and serves.
- The same workspace key routed three times selects the same instance all three
  times.
- Twelve distinct keys distributed across both buckets — seven and five.

---

## Keeping the single-instance development setup working

The default stack runs exactly one server, reachable as `register-server:8090`,
and the documented development workflow depends on it. A configuration naming
`register-server-1` and `register-server-2` would break that.

The entrypoint already substitutes `__RESOLVER__` and `__BACKEND__` into the
template at container start. It gains one more responsibility: generating the
`split_clients` block from a list of backend hosts.

- New environment variable `BACKEND_INSTANCES`, a space-separated list of host
  names. **Default: unset.**
- Unset, or a single entry, generates a single catch-all bucket
  (`* "<host>";`), which routes every workspace to the one instance. This is
  behaviourally identical to today, so the default development and production
  stacks are unaffected.
- Two or more entries generate evenly divided buckets with the last as `*`.

Multi-instance operation is therefore opt-in, exercised by a dedicated compose
override rather than by changing the default stack. The default stack keeps
`container_name` and its fixed host port, so `docker compose up -d
register-server`, `localhost:8090` health checks, and every documented workflow
continue to work unchanged.

---

## File inventory

The file inventory lives in its own document, `PLAN-NGINX-WORKSPACE-ROUTING-INVENTORY.md`,
which the approval hook reads and only the user writes.

---

## Verification plan

### Configuration validity

```bash
docker build -f containers/prod/Dockerfile.frontend-prod -t local/frontend:test .
docker run --rm --entrypoint nginx --user root local/frontend:test -t -c /etc/nginx/nginx.conf
```

Must report the syntax is ok and the test is successful.

### Startup with the backend absent

Start the frontend container with no server running. nginx must start and serve
the SPA shell. This is the property the upstream-block approach would have
destroyed, so it is tested explicitly rather than assumed.

### Masking

Issue a workspace request and read the container log. The key must not appear;
the method, masked path, status and duration must.

```bash
docker compose --profile frontend up -d
curl -s -o /dev/null "http://localhost:18080/w/<key>/trees"
docker compose logs frontend | tail -5
```

The acceptance condition is exact: **the key string does not appear anywhere in
the output.** Checking for `/w/***` is not sufficient — the test is the absence
of the key, not the presence of the mask.

### Affinity

Bring up the multi-instance override. Issue several requests for one workspace
key and confirm from the server logs that they all reach the same instance.
Repeat with a second key that buckets differently and confirm it consistently
reaches the other one.

### Events across instances

With the multi-instance stack running, open the events endpoint for a tree with
`curl -N`, then mutate that tree through a separate request. The mutation event
must arrive on the open connection. This is the test that fails today and is
the reason the change exists.

### Smoke suites

After the mandatory cleanup of the leaked compose stack:

```bash
docker compose -p register down -v --remove-orphans 2>/dev/null || true
```

run BATS suite C (in-memory, the fast gate) and suite A (end-to-end with Irmin
persistence), since both exercise nginx routing. Report pass or fail only.

---

## Sequencing

Independent of every other open plan — it touches no Scala source and no file
any other plan touches. It can land first.

Within the plan, the log masking is independent of the routing and could land
alone if the routing needs more discussion. They share the extracted `$ws_key`
variable, which is why they are one plan rather than two changes racing on the
same file.

---

## Version bump

PATCH. Shipped configuration changes, no external API change.
