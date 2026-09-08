# CONCEPT_POST.md — share this one (short version)

> Live as Discussion #21: https://github.com/chrisalunlloyd2-sudo/mindpalace/discussions/21
> Also drop in Reddit/Discord/DM. Short by design —
> the longer threads live in docs/OUTREACH_POSTS.md.

---

**MindPalace — walk your own code. Tell me if this is interesting.**

I turned my GitHub account into a Doom-style palace. Every door is a
real repository. Every book on the shelves is a real file you can open,
edit, and push from inside the game. Private repos glow pink; fog of war
hides what you haven't explored.

The part I'm most interested in: the palace is inhabited. Two small
language models (1B-class, fully local via Ollama) walk the halls as
agents — they have bodies, DePIN-style wallets that price every model
call, and a 3-voter quorum that gates what they're allowed to do.
They read your code and raise real GitHub issues. A critic reviews
their concrete actions; a gate kills the meta-chatter loops small
models fall into (the log-proven failure modes are documented).

The palace also finishes itself: a 150-step autonomous program executes
one hypothesis at a time, and every shipped step carries machine-verified
evidence — build -> 40-check selftest -> 13-waypoint E2E with pixel-decoded
screenshot proof -> a public append-only step-log. Same repos in, same
world out, frame for frame — determinism is a contract, which is what
makes that proof possible.

Everything runs local. Your code, your GPU, your Ollama models. No cloud,
no telemetry, no API keys. `--demo` boots a 12-room fixture palace with
zero auth and zero network — that's the same mode CI verifies.

See it move (11 real in-game waypoints):
https://chrisalunlloyd2-sudo.github.io/mind-palace/demo.html

Repo (installer, docs, 15-minute first PR — no Java needed):
https://github.com/chrisalunlloyd2-sudo/mindpalace

**What I want from you:** feedback on the concept before beta widens —
- Is "your code as a place agents inhabit" useful to you, or just pretty?
- Which of these would you actually use: time-slider (read files at any
  commit), activity heatmap on the doors, in-game editor?
- If you run it: one bug report with the smoke pass beats ten likes.
  [docs/TEST_PLAN.md](https://github.com/chrisalunlloyd2-sudo/mindpalace/blob/main/docs/TEST_PLAN.md) — 12 surfaces, 10 minutes.

Nothing lives forever, nothing runs for free, always advancing.
