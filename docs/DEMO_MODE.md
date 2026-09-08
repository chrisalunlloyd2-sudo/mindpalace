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

## Quick win: add a fixture room (10 minutes, no code)

Contributors shape the demo world without touching Java:

1. Create `data/demo_repos/<your-name>/` with 2–5 small real files
   (any language — the spines color-code by extension).
2. Append one object to `data/demo_repos.json`:

        { "name": "your-name",
          "language": "Python",
          "description": "one line shown on the door sign",
          "private": false,
          "path": "data/demo_repos/your-name" }

3. `--demo` and walk to your room (search `/your-name`).
4. PR it. That's the whole contract — the manifest regex and room builder
   handle the rest.

House rules: files must be yours or permissively licensed (they ship in
the repo), keep each file < 50 lines, no binaries. Your room appears in
every future demo boot and the CI smoke pass.

## Hosted tour (no install at all)

[demo.html on GitHub Pages](https://chrisalunlloyd2-sudo.github.io/mind-palace/demo.html)
presents the 11-waypoint verification tour + stills with run-it-locally
CTAs. Static by nature — the interactive demo is `--demo` locally.
