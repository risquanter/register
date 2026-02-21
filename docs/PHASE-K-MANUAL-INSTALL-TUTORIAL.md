# Phase K Manual Installation  (Linux, hardened)

This document is still command-first, but now includes explicit secure-by-default guidance.

- Scope: single-node `k3s` for local + realistic dev (scaled-down prod-like)
- Goal 1: quick enterprise-auth trial on localhost
- Goal 2: durable dev cluster with sane security defaults
- Principle: idempotent commands whenever possible (`apply`, `upgrade --install`, `--overwrite`)
- Constraint: no dedicated secret-manager service in this baseline

---

## Security posture used in this guide

1. **Least privilege** for credentials (especially GHCR token).
2. **No plaintext secrets on CLI history** (use `read -s`, files with `0600`, stdin pipes).
3. **Namespace scoping** for secrets and service accounts (no cluster-wide default patching).
4. **Defense in depth**: Pod Security labels, RBAC, network policies, secret encryption at rest.
5. **Explicit limitations** documented at the end.

---

## 0) Prerequisites (one-time)

### 0.1 Install base tools

```bash
# update package index
sudo apt update
# install foundational tools used by later steps
sudo apt install -y curl jq git openssl ca-certificates gnupg lsb-release
```

### 0.2 Install kubectl (pin version)

```bash
# choose kubectl matching your target k3s minor version (±1 minor skew)
K8S_VERSION="v1.35.1"

# download kubectl binary and matching sha256 checksum
curl -fsSLO "https://dl.k8s.io/release/${K8S_VERSION}/bin/linux/amd64/kubectl"
curl -fsSLO "https://dl.k8s.io/${K8S_VERSION}/bin/linux/amd64/kubectl.sha256"

# verify integrity before installing
echo "$(cat kubectl.sha256) kubectl" | sha256sum --check

# install binary
chmod +x kubectl
sudo mv kubectl /usr/local/bin/kubectl
rm -f kubectl.sha256

# verify client
kubectl version --client
```

### 0.3 Install Helm

```bash
# install helm via official script (acceptable for local/dev bootstrap)
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# verify install
helm version
```

### 0.4 Verify Docker

```bash
# ensure docker CLI and daemon are available
docker --version
docker info >/dev/null
```

---

## 1) Phase K.1 — k3s bootstrap (with hardening flags)

### 1.1 Install k3s with secret encryption enabled

```bash
# install k3s server with:
# - secrets encryption at rest enabled
# - kubeconfig mode 600 (owner-only)
# - traefik disabled (optional; if you use another ingress stack)
curl -sfL https://get.k3s.io | \
  INSTALL_K3S_EXEC="server --secrets-encryption --write-kubeconfig-mode=600 --disable traefik" \
  sh -
```

### 1.2 Verify secret encryption status

```bash
# verify secret encryption provider status
sudo k3s secrets-encrypt status
```

### 1.3 Configure kubeconfig for your user

```bash
# create user kube dir
mkdir -p ~/.kube

# copy admin kubeconfig
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown "$USER":"$USER" ~/.kube/config
chmod 600 ~/.kube/config

# local usability tweak
sed -i 's/127.0.0.1/localhost/g' ~/.kube/config

# verify cluster reachability
kubectl get nodes -o wide
```

### 1.4 Create base namespaces + baseline labels

```bash
# create namespaces idempotently
kubectl create namespace register --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace infra --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace observability --dry-run=client -o yaml | kubectl apply -f -

# enforce Pod Security admission labels (restricted baseline)
kubectl label ns register \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted \
  --overwrite

kubectl label ns infra \
  pod-security.kubernetes.io/enforce=baseline \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted \
  --overwrite

# verify
kubectl get ns --show-labels | grep -E 'register|infra|observability'
```

### 1.5 Install cert-manager

```bash
helm repo add jetstack https://charts.jetstack.io --force-update
helm repo update

helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --set crds.enabled=true

kubectl -n cert-manager rollout status deploy/cert-manager --timeout=180s
kubectl -n cert-manager rollout status deploy/cert-manager-cainjector --timeout=180s
kubectl -n cert-manager rollout status deploy/cert-manager-webhook --timeout=180s
```

### 1.6 (Optional) metrics-server

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl -n kube-system rollout status deploy/metrics-server --timeout=180s
kubectl top nodes || true
```

---

## 2) Phase K.2 — GHCR pull path (least privilege + no CLI secret leakage)

### 2.0 GitHub token requirements

- Prefer **fine-grained PAT** scoped only to the required package/repo.
- Minimum permission: read access for package pulls.
- Short expiry and regular rotation.
- Do not store token in shell startup files.

### 2.1 Read credentials securely (non-export, one-time shell vars)

```bash
# disable command echo for secret entry; do not export variables
read -r -p "GitHub username: " GHCR_USER
read -r -s -p "GHCR token (read-only): " GHCR_PAT; echo

# optional safety guard: ensure token is non-empty
test -n "$GHCR_PAT"
```

### 2.2 Create a dedicated runtime service account (do NOT patch default SA)

```bash
# create dedicated SA used by app pods
kubectl -n register create serviceaccount register-runtime \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2.3 Create namespace-scoped imagePullSecret idempotently

```bash
# create/update pull secret in register namespace only
kubectl -n register create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username="$GHCR_USER" \
  --docker-password="$GHCR_PAT" \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2.4 Attach imagePullSecret to dedicated SA only

```bash
# idempotent patch: service account used by your deployment only
kubectl -n register patch serviceaccount register-runtime --type='merge' \
  -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'

# verify
kubectl -n register get sa register-runtime -o yaml | grep -A3 imagePullSecrets
```

### 2.5 Immediately remove local secret material

```bash
# clear in-memory vars from current shell
unset GHCR_PAT
unset GHCR_USER

# if docker login was used manually, ensure config file is owner-only
test -f ~/.docker/config.json && chmod 600 ~/.docker/config.json || true
```

> GitHub best practice for CI/CD: prefer GitHub Actions + OIDC/federated identity to avoid long-lived static secrets in pipelines.

---

## 3) Phase K.3 — PostgreSQL (hardened install pattern)

### 3.1 Create a local values file with strict permissions

```bash
# create temp values file with owner-only permissions
umask 077
cat > /tmp/register-postgres-values.yaml <<'YAML'
auth:
  postgresPassword: "REPLACE_ME_STRONG_POSTGRES_PASSWORD"
primary:
  persistence:
    enabled: true
    size: 10Gi
  containerSecurityContext:
    enabled: true
    runAsNonRoot: true
YAML
```

### 3.2 Install/upgrade chart idempotently

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami --force-update
helm repo update

helm upgrade --install register-postgres bitnami/postgresql \
  --namespace infra \
  --create-namespace \
  -f /tmp/register-postgres-values.yaml

kubectl -n infra rollout status statefulset/register-postgres-postgresql --timeout=300s
kubectl -n infra get pvc
```

### 3.3 Create databases idempotently

```bash
# create DBs only if missing
kubectl -n infra exec register-postgres-postgresql-0 -- bash -lc '
  psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = '\''register_app'\''" | grep -q 1 || psql -U postgres -c "CREATE DATABASE register_app";
  psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = '\''keycloak'\''" | grep -q 1 || psql -U postgres -c "CREATE DATABASE keycloak";
'
```

### 3.4 Validate connectivity

```bash
kubectl -n infra exec register-postgres-postgresql-0 -- bash -lc "psql -U postgres -d register_app -c 'SELECT 1;'"
kubectl -n infra exec register-postgres-postgresql-0 -- bash -lc "psql -U postgres -d keycloak -c 'SELECT 1;'"
```

### 3.5 Cleanup temporary secret file

```bash
shred -u /tmp/register-postgres-values.yaml
```

---

## 4) Phase K.4 — Keycloak (external DB, hardened secret flow)

### 4.1 Create values file with minimal secret exposure

```bash
umask 077
cat > /tmp/keycloak-values.yaml <<'YAML'
auth:
  adminUser: "admin"
  adminPassword: "REPLACE_ME_STRONG_ADMIN_PASSWORD"
postgresql:
  enabled: false
externalDatabase:
  host: register-postgres-postgresql.infra.svc.cluster.local
  port: 5432
  user: bn_keycloak
  password: "REPLACE_ME_STRONG_KEYCLOAK_DB_PASSWORD"
  database: keycloak
containerSecurityContext:
  enabled: true
  runAsNonRoot: true
YAML
```

### 4.2 Install Keycloak idempotently

```bash
helm upgrade --install keycloak bitnami/keycloak \
  --namespace infra \
  -f /tmp/keycloak-values.yaml

kubectl -n infra rollout status statefulset/keycloak --timeout=300s
```

### 4.3 Bootstrap realm locally

```bash
kubectl -n infra port-forward svc/keycloak 8081:80
```

Then configure:

- realm: `register`
- clients: `register-api` (confidential), `register-web` (public + PKCE)
- mappers: `sub -> x-user-id`, `email -> x-user-email`, roles claim

### 4.4 Verify OIDC metadata

```bash
curl -s http://localhost:8081/realms/register/.well-known/openid-configuration | jq .issuer
curl -s http://localhost:8081/realms/register/protocol/openid-connect/certs | jq .keys[0].kid
```

### 4.5 Cleanup temp values

```bash
shred -u /tmp/keycloak-values.yaml
```

---

## 5) Phase K.5 — Istio ambient + auth hardening

### 5.1 Install Istio CLI

```bash
curl -L https://istio.io/downloadIstio | sh -
ISTIO_DIR=$(ls -d istio-*/ | head -n1)
export PATH="$PWD/${ISTIO_DIR}bin:$PATH"
istioctl version --remote=false
```

### 5.2 Install ambient profile

```bash
istioctl install -y --set profile=ambient
kubectl -n istio-system get pods
```

### 5.3 Enroll namespace + waypoint

```bash
kubectl label namespace register istio.io/dataplane-mode=ambient --overwrite
istioctl waypoint apply -n register --enroll-namespace
kubectl -n register get gateway
```

### 5.4 Apply JWT + policy resources

```bash
kubectl apply -f infra/k8s/istio/request-authentication.yaml
kubectl apply -f infra/k8s/istio/authorization-policy.yaml
```

### 5.5 Mesh trust checks

```bash
# invalid JWT should fail before app logic
curl -i -H "Authorization: Bearer INVALID" https://<INGRESS>/w/<key>/risk-trees

# forged identity header must not grant access
curl -i -H "x-user-id: 00000000-0000-0000-0000-000000000001" https://<INGRESS>/w/<key>/risk-trees
```

Expected: invalid JWT `401`; forged identity rejected/ignored.

---

## 6) RBAC + namespace hardening add-ons (recommended)

### 6.1 Restrict who can read secrets in `register`

```bash
# role deliberately excludes secrets read permissions
cat <<'YAML' | kubectl apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: register-dev-operator
  namespace: register
rules:
  - apiGroups: [""]
    resources: ["pods","services","configmaps","events"]
    verbs: ["get","list","watch","create","update","patch","delete"]
  - apiGroups: ["apps"]
    resources: ["deployments","statefulsets","daemonsets","replicasets"]
    verbs: ["get","list","watch","create","update","patch","delete"]
YAML
```

Bind only trusted users/groups to this role.

### 6.2 Default-deny network policy (register namespace)

```bash
cat <<'YAML' | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: register
spec:
  podSelector: {}
  policyTypes: ["Ingress","Egress"]
YAML
```

Then add explicit allow policies for required traffic paths.

---

## 7) Phase K.6 — CI/CD minimum with GitHub security practices

1. PR workflow: format/lint/test/SCA.
2. Main workflow: build + push GHCR image with immutable tag (`git-sha`).
3. Deploy workflow: Helm deploy to `local-dev`.
4. Provisioning workflow: SpiceDB graph reconcile.
5. Rollback runbook: `helm history` + `helm rollback`.

Security controls in GitHub:

- Enable branch protection + required reviews.
- Enable Dependabot + secret scanning + push protection.
- Prefer OIDC federation for cloud auth; avoid long-lived cloud keys.
- Use environment protections for non-dev deploys (required reviewers).

Validation:

```bash
helm -n register ls
helm -n register history <release-name>
kubectl -n register get pods
kubectl -n register get events --sort-by=.lastTimestamp | tail -n 30
```

Rollback:

```bash
helm -n register rollback <release-name> <revision>
```

---

## 8) Operational hygiene (rotate/revoke/audit)

### 8.1 Rotate GHCR token quickly

1. Revoke old token in GitHub.
2. Create new least-privilege token.
3. Recreate secret:

```bash
read -r -p "GitHub username: " GHCR_USER
read -r -s -p "New GHCR token: " GHCR_PAT; echo

kubectl -n register create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username="$GHCR_USER" \
  --docker-password="$GHCR_PAT" \
  --dry-run=client -o yaml | kubectl apply -f -

unset GHCR_USER GHCR_PAT
```

### 8.2 Audit checkpoints

```bash
kubectl -n register get events --sort-by=.lastTimestamp | tail -n 40
kubectl -n register get secret ghcr-pull -o yaml >/dev/null
kubectl auth can-i get secrets -n register --as=<subject>
```

---

## 9) Fast health-check bundle

```bash
kubectl get nodes
kubectl get ns
kubectl -n infra get pods
kubectl -n register get pods
kubectl -n cert-manager get pods
kubectl -n istio-system get pods
kubectl top nodes || true
```

---

## 10) Security limitations / operational assumptions (must-read)

This setup is intentionally simple and does **not** eliminate all risk.

1. **No dedicated secret-manager service** in baseline:
   - Kubernetes Secrets are still base64 objects in etcd.
   - Mitigation here: at-rest encryption + strict RBAC + minimal secret lifetime in shell.

2. **Single-node k3s** is not HA:
   - Node compromise = cluster compromise.
   - Good for local/dev realism, not production resilience.

3. **Installer convenience scripts** (`curl | sh`) are used:
   - Acceptable for local bootstrap, weaker than fully pinned artifact verification.

4. **Port-forwarded admin surfaces** (Keycloak):
   - Keep sessions short, avoid public exposure, close terminal when done.

5. **Default-deny network policy requires explicit allow rules**:
   - If omitted, pods may have broader east-west access than intended.

6. **Human-operated secrets handling remains a risk**:
   - Shoulder surfing, terminal logging, shell history mistakes are still possible.
   - Mitigate with `read -s`, non-exported vars, immediate `unset`, short token TTL.

If you later need stronger controls without running a full external secret manager service, next incremental option is encrypted GitOps secrets (e.g., SOPS/age or Sealed Secrets) with strict key custody and rotation.
