---
name: Docs issue
about: Documentation wrong, stale, or missing
labels: documentation
---
**Which doc** (ARCHITECTURE.md / CONTRIBUTING.md / DEV_SETUP.md / docs/*):

**What's wrong**:
- [ ] Stale — code moved, doc didn't (the known planning-doc drift problem)
- [ ] Wrong — says something the code contradicts
- [ ] Missing — a system/flag/gotcha with no page
- [ ] Unclear — exists but hard to follow

**The fix in one sentence**:

**Proof of staleness** (for stale/wrong): file:line of the code that disagrees.

**Note**: every drafted task carries a "verify before you build" rule —
planning docs run behind shipped code by design; grep first, don't trust.
