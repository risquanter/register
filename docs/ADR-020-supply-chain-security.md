# ADR-020: Frontend Supply Chain Security — npm Hardening

**Status:** Accepted  
**Date:** 2026-02-11  
**Tags:** security, npm, supply-chain, frontend, dependencies

---

## Context

- The frontend depends on npm packages (Vite, Scala.js plugin, Geist font) — **142 transitive packages** total
- npm's default behaviour runs arbitrary install scripts (`postinstall`, `preinstall`) from any package in the dependency tree
- Supply chain attacks via install scripts are a proven vector (event-stream 2018, ua-parser-js 2021, Shai-Hulud worm 2025)
- Our dependency count is small (3 direct) but each carries a transitive tree that changes on `npm install`
- SemVer range specifiers (`^`, `~`) allow silent version drift — a compromised patch release installs automatically

---

## Decision

### 1. Disable Install Scripts by Default

`.npmrc` at project root blocks all install scripts. Only explicitly rebuilt packages run scripts:

```ini
# modules/app/.npmrc
ignore-scripts=true
save-exact=true
```

Legitimate packages needing scripts (e.g., `esbuild` downloads a platform binary) are rebuilt manually:

```bash
npm rebuild esbuild
```

### 2. Pin Exact Versions

`save-exact=true` writes `"vite": "6.4.1"` instead of `"vite": "^6.4.1"`.
Existing ranges in `package.json` should be replaced with exact versions.
Updates happen explicitly via `npm update <pkg>` followed by review:

```jsonc
// GOOD: Exact versions — no silent drift
"devDependencies": {
  "@scala-js/vite-plugin-scalajs": "1.1.0",
  "vite": "6.4.1"
}
```

### 3. Pre-Install Audit Checklist

Before adding any new npm dependency:

1. Check [socket.dev](https://socket.dev) for the package — review supply chain score
2. Run `npm audit` after install — zero tolerance for known vulnerabilities
3. Inspect install scripts: `npm pack <pkg> && tar -tf <pkg>-*.tgz | grep -i install`
4. Prefer packages with **zero install scripts** and **small dependency trees**
5. For font/asset packages — consider self-hosting static files instead of the npm package

### 4. Self-Host Static Assets When Practical

Font packages like `geist` are pure CSS + font files under permissive licenses (SIL OFL 1.1).
Copying the font files into the project removes the npm dependency entirely:

```
modules/app/
  fonts/
    geist-sans/          ← copied from node_modules/geist
      style.css
      *.woff2
    geist-mono/
      style.css
      *.woff2
    LICENSE.txt          ← SIL OFL 1.1 requires this
```

This eliminates 1 direct dependency and its entire transitive risk surface.

### 5. Periodic Maintenance

Run monthly (or before each release):

```bash
npm audit                          # known vulnerabilities
npm outdated                       # stale dependencies
npm ls --all | wc -l               # monitor transitive growth
npm ls --all | grep 'install'      # check for new install scripts
```

---

## Code Smells

### ❌ SemVer Ranges in package.json

```jsonc
// BAD: Range allows silent drift to compromised patch
"dependencies": {
  "geist": "^1.7.0"
}

// GOOD: Exact pin — updates require explicit action
"dependencies": {
  "geist": "1.7.0"
}
```

### ❌ Installing Without Audit

```bash
# BAD: Blind install — scripts run, no review
npm install some-fancy-package

# GOOD: Check first, install with scripts blocked
# 1. Review on socket.dev
# 2. npm install some-fancy-package   (scripts blocked by .npmrc)
# 3. npm audit
# 4. npm rebuild some-fancy-package   (only if scripts are needed)
```

### ❌ Large Transitive Trees for Simple Assets

```jsonc
// BAD: npm package for static font files
"dependencies": {
  "geist": "1.7.0"    // pulls npm metadata, lockfile churn
}

// GOOD: Self-hosted — no npm dependency at all
// fonts/geist-sans/style.css + *.woff2 committed to repo
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `modules/app/.npmrc` | `ignore-scripts=true`, `save-exact=true` |
| `modules/app/package.json` | Exact version pins (no `^` or `~`) |
| `npm rebuild esbuild` | Manual rebuild for legitimate install scripts |
| Pre-install checklist | socket.dev review, `npm audit`, script inspection |
| `modules/app/fonts/` | Future: self-hosted Geist font files + LICENSE.txt |

---

## References

- [socket.dev](https://socket.dev) — npm package supply chain analysis
- [SIL Open Font License 1.1](https://openfontlicense.org) — permits bundling font files with software
- [npm ignore-scripts](https://docs.npmjs.com/cli/v10/using-npm/config#ignore-scripts) — official npm docs
