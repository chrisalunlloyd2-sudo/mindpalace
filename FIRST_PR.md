# FIRST_PR.md — your first contribution, worked end to end

> The demo-mode fixture room is the lowest-barrier real contribution:
> no Java, no build, no dev environment beyond git + a text editor.
> This is the whole journey as a story, with every command and the
> output you should expect at each step. Budget: 15 minutes if the
> build machine is warm, ~30 cold.

## Before you start

You need: git, a GitHub account, any text editor. That's it — no JDK,
no Maven, no Ollama. (You only need the JDK if you want to *see* your
room by running the game locally; not required for the PR itself.)

## Step 1 — make your room's files (3 min)

Create a folder with 2–5 tiny text files. Example — a "poetry" room:

```
data/demo_repos/poetry-corner/
├── haiku.md
└── limerick.md
```

`haiku.md`:
```markdown
# Build Haiku

green threads in the fog
repos bloom behind each door
the rotor ticks on
```

House rules (from docs/DEMO_MODE.md): files you own or permissively
licensed, each < 50 lines, no binaries. The spines color-code by
extension, so pick extensions you want to see.

## Step 2 — register it in the manifest (1 min)

Append to `data/demo_repos.json` (it's a JSON array — keep the commas
balanced; the file has 12 objects already, yours is #13):

```json
{ "name": "poetry-corner",
  "language": "Markdown",
  "description": "Small poems about building",
  "private": false,
  "path": "data/demo_repos/poetry-corner" }
```

Fields: `name` = door label + search key (lowercase-safe); `language`
drives book-spine color; `description` renders on the room's sign;
`private` paints the door pink instead of cyan.

## Step 3 — see it work (5 min, optional but recommended)

If you have the JDK:

```
java -Dprism.order=sw -Dprism.vsync=false -jar mindpalace-live.jar --demo
```

Expected console line: `[RepoMapper] Demo fixtures loaded: 13 rooms (no
auth, no network)` — count goes 12 → 13. In-game: press `/`, type
`poetry`, Enter — you should land at your room's door.

No JDK? The CI smoke pass runs `--demo` on every PR and will confirm
the fixture loads for you.

## Step 4 — open the PR (5 min)

1. Fork → branch (`fixture/poetry-corner`) → commit → push.
2. PR title: `fixture: poetry-corner room`.
3. Body: what the room is, one screenshot if you ran it locally (F12
   saves a PNG), and the line "demo fixtures: 12 → 13".

A maintainer checks: manifest parses, files are legal, room appears.
Merge. Your room ships in every future demo boot, every CI run, and
the step-log notes it.

## What "done" looks like

- `--demo` console: `Demo fixtures loaded: 13 rooms`
- No GitHub token, no network calls in the boot log
- Selftest still `40 passed, 0 failed` (demo mode must never break it)

## If something goes wrong

- Manifest parse error → check commas/quotes (strict JSON); the console
  prints `demo manifest parse failed` with the reason, and the game
  falls back to a local scan rather than crashing — fix the JSON, retry.
- Room missing in-game → `path` must be relative to the repo root and
  the folder must actually exist; search is exact-prefix on `name`.
- Anything else: the [bug template](.github/ISSUE_TEMPLATE/bug_report.md)
  routes past the known traps.

## After your first PR

The same pattern scales up: the layout-algorithm lane (an afternoon, one
file) is the same shape — copy a working example, change the math, prove
it, PR. See docs/LAYOUT_ALGORITHMS.md's quick-win section. Welcome to
the palace.