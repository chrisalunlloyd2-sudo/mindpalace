# Demo Mode — run the palace with zero setup

Demo mode boots the full 3D world from a bundled JSON snapshot instead of
GitHub — no PAT, no network, no Ollama. Built for reviewers, designers,
CI smoke tests, and "just let me see it" moments.

## Run it

    java -Dprism.order=sw -Dprism.vsync=false -jar mindpalace-live.jar --demo

That's the whole contract. Everything works exactly as in live mode —
walking, doors, books, rotor, music — but the world is built from
`data/demo_repos.json` (committed in the repo) and GitHub sync is skipped.

## What differs in demo mode

| Live mode | Demo mode |
|-----------|-----------|
| 143 rooms from your real GitHub repos | 12 fixture rooms (varied languages/sizes) |
| PAT from Windows Credential Manager | no auth, no API calls |
| Live repo sync (hourly) | static snapshot |
| Agents chat via local Ollama (optional anyway) | identical — still optional |

The fixture data lives in `data/demo_repos.json` — plain JSON: repo name,
language, size, file list. Edit it to shape your own demo palace.

## CI smoke test

The selftest + E2E already run headless; add `--demo` for a fully
hermetic run (no GitHub rate limits on CI runners):

    java -jar mindpalace-1.0.0.jar --selftest --demo

## Status

Planned as step 112 in NEXT_100_STEPS.md (content & depth block). This
page documents the contract ahead of the implementation so the flag name
and file path are stable from day one.
