# COMMUNITY.md — outreach playbook

> How MindPalace meets the outside world. Keep this short and honest —
> outreach that outpaces shipped reality erodes trust; the step-log exists
> precisely so we never have to over-claim.

## The pitch (one paragraph, use verbatim)

MindPalace is a Doom-style 3D explorer for your GitHub account: every door
is a real repo, every book a real file, and two small language models live
inside the walls — reading, gossiping, and mining your code while you walk.
It ships itself autonomously (the Finishing Program) with machine-verified
evidence at every step, runs fully local (your code, your GPU, your Ollama
models), and needs zero cloud. The demo flag boots a full palace with no
setup.

## Surfaces (maintained)

| Surface | What | Owner cadence |
|---------|------|---------------|
| README hero + gallery | real E2E screenshots | refresh on any visual milestone |
| Step-log issue #9 | append-only evidence per step | every cascade run (automated) |
| Releases | installer + release notes per phase | every version bump |
| GitHub Pages site | palace personality + vision | on visual milestones |
| FEATURE_BACKLOG | scored tiers + linked issues | on triage (see LABELS.md) |
| `good first issue` | newcomer funnel — 10 open (#13–#18, #22–#25): fixture room, manifest polish, gotcha rows, INSTALLER skeleton, panel screenshots, teleporter check, window title, Quick Start demo line, Win11 row, CI smoke | keep ≥ 6 open while the funnel is young |

## Announcement checklist (every minor release)

1. Fresh installer verified end-to-end (see INSTALLER.md when it lands — issue #11).
2. Release notes = that phase's step-log digest (the weekly self-report format).
3. Hero screenshot refreshed if any visual changed.
4. Post: repo README already carries it; optional channels below.

## Optional channels (only after the above pass)

- **Reddit r/localLLaMA** — the fully-local SLM-agents-in-a-game angle is
  the unique hook for that audience; lead with the agents, not the game.
- **Hacker News (Show HN)** — lead with the autonomous finishing program +
  evidence trail; the determinism contract is the technically interesting
  part.
- **YouTube/dev-log** — the rotor-clock music + gold quorum flares are the
  most clip-able moments; 90-second capture via the E2E tour.
- **The BDI_FSM_AGENT repo** — cross-link as the symbolic-AI sibling.

## Tone rules

- Never announce what isn't shipped (the step-log keeps us honest).
- Lead with what runs local — that's the differentiator, not the graphics.
- Every claim links to its evidence. If there's no evidence yet, the
  claim isn't ready.
- Community questions land in issues (templates handle triage), not DMs.

## Newcomer funnel (measure, don't guess)

- Time-to-walking: installer → walking around, target < 2 min (issue #11
  closes this with INSTALLER.md).
- Time-to-first-PR: good-first-issue → merged, tracked via the step-log.
- Weekly digest: `python scripts/feedback_digest.py --days 7` (script-only,
  no LLM) prints window issues/discussions + funnel health; append its
  output to the Sunday review pack. The welcome-feedback workflow greets
  first-time issues/PRs and every discussion with the 3 feedback questions.
- Both numbers go into the weekly self-report (step 148's four numbers +
  these two = the Sunday review pack).