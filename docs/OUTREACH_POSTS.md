# OUTREACH_POSTS.md — the demo artifact + ready-to-post threads

> Social copy, written now, posted when the announcement checklist passes
> (COMMUNITY.md): installer verified, notes = step-log digest, hero fresh.
> Tone rules apply: never announce what isn't shipped; lead with local;
> every claim links to evidence.

## The demo artifact

**`docs/screenshots/mindpalace_tour.gif`** — 11 real E2E waypoints as a
640×360 looping tour (435 KB), dark artifacts excluded per the evidence
rules. Embedded below the README hero. Hosted in-repo, so the "hosted
demo" = GitHub serves it wherever the README renders.

**Runnable demo**: the `--demo` flag (12-room fixture palace, zero auth,
zero network) is the hosted demo's always-works fallback — and
[issue #20](https://github.com/chrisalunlloyd2-sudo/mindpalace/issues/20)
tracks a human clean-machine run of it.

---

## Tweet/X thread (7 posts)

**1/7**
I built a Doom-style 3D explorer for your GitHub account.

Every door is a real repo. Every book a real file. Two small LLMs live in
the walls — reading, gossiping, mining your code while you walk.

[attach: mindpalace_tour.gif]

**2/7**
The twist: it ships itself.

An "Autonomous Finishing Program" — 150 hypotheses executed by script-only
bots, one per cycle. Every step ships with machine-verified evidence:
build → selftest → E2E screenshot proof → public step-log.

**3/7**
The agents aren't decoration. They have:

• wallets (every LLM call is priced in credits)
• a 3-voter quorum that gates actions
• a critic that reviews their CONCRETE actions, not vibes
• anti-loop directives — we caught the cure feeding the disease

**4/7**
Everything runs local. No cloud, no API keys, no telemetry:

• your code on your disk
• Ollama models ≤ 1.5B (cold-shot: one model in RAM at a time)
• Intel HD 510 runs it at 30fps — budgets documented, not vibes

Your code never leaves the machine.

**5/7**
`--demo` boots a full 12-room fixture palace with zero auth and zero
network. No GitHub account. No Ollama. It's the same mode CI verifies —
so the demo you see is the demo that's tested.

**6/7**
Determinism is a feature: same repo list → same world, frame for frame.
That's why 13 labeled waypoints can prove regressions pixel by pixel —
and why the two intentionally-dark frames are documented baseline
artifacts, not bugs.

**7/7**
Repo, evidence log, and a 15-minute first-PR path (no Java needed —
add a fixture room):

github.com/chrisalunlloyd2-sudo/mindpalace

Nothing lives forever, nothing runs for free, always advancing.

---

## Hacker News (Show HN)

**Title:** `Show HN: MindPalace – a 3D game where GitHub repos are rooms and small LLMs mine your code`

**Body:**

I turned my GitHub account into a walkable palace: Doom-style corridors,
every door opens into a room built from a real repository, every book on
the shelves is a real file you can edit and save back to GitHub from
inside the game.

The part I think is interesting isn't the graphics (it runs on an Intel
HD 510). It's the inhabitants: two local SLMs (1B-class) with bodies,
DePIN wallets that price every model call, and a quorum vote before they
act. The chat logs were 81.8% meta-chatter, so we built a three-layer
pipeline against it — prompts that only contain real work, a bigram-similarity
repetition breaker, and an emit-side gate built from the actual loop
openers in the logs. The interesting failure we found: the original
"fix" for repetition was itself being answered conversationally by the
model — the cure was feeding the disease.

The palace also finishes itself. A script-only bot pipeline executes
150 hypothesis steps one at a time; each ships through a cascade
(build → 40-check selftest → 13-waypoint E2E with pixel-decoded PNG
proof → commit → push → append-only public step-log). Determinism is a
hard contract: same repo list produces the same world frame for frame,
which is what lets the E2E tour prove regressions pixel by pixel.

Runs fully local — your code, your GPU, your Ollama models, no telemetry.
`--demo` boots a 12-room fixture palace with no account and no network
(that's what CI tests). Java 17 + Maven, Windows/Linux, OpenGL 3.3.

Repo: https://github.com/chrisalunlloyd2-sudo/mindpalace
Evidence log (every step, machine-verified): https://github.com/chrisalunlloyd2-sudo/mindpalace/issues/9

Ask: people with non-Intel GPUs running the smoke pass
(docs/TEST_PLAN.md) — the environment matrix needs your row.

---

## r/localLLaMA post

**Title:** `My local 1B models live inside a 3D game and mine my code — fully offline`

MindPalace is a first-person GitHub explorer where two local Ollama
agents (llama3.2:1b + qwen2.5:0.5b, deepseek tie-breaker) walk the halls
of a palace built from your repos. They're not chatbots in a sidebar —
they have bodies, wallets, and a quorum. Every model call is priced in
credits; a critic reviews their concrete actions.

Fully local: keep_alive=0, one model resident at a time (the cold-shot
gate), runs on an Intel HD 510. The agents read your actual code and
raise GitHub issues — a critic reviews the diffs, a 3-voter quorum votes.

Demo with zero setup: `--demo` (12 fixture rooms, no auth, no network).
The whole evidence trail is public: 150-step roadmap, every step shipped
with build/selftest/E2E-PNG proof.

What we learned building this might be the most useful part: small-model
failure modes you can't see with frontier models — repetition loops that
evade exact-match detection, the cure-feeding-the-disease prompt bug,
context budgets under 4k. All documented with fixes.

github.com/chrisalunlloyd2-sudo/mindpalace

---

## Posting checklist (from COMMUNITY.md)

- [ ] Installer verified end-to-end (issue #11 closes this)
- [ ] Release notes = step-log digest for the phase
- [ ] Hero/GIF refreshed if visuals changed (done — the tour GIF)
- [ ] Tone check: every claim above links to evidence or names its metric
- [ ] Pick day + time (Tue–Thu, 9–11am ET for HN)