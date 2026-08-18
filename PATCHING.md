# Live Patching — update the game WHILE it's running

The game watches `patches/patch.json`. When a **new** patch (new `id`) appears,
the game plays the **GAME PATCH LOADING** cinematic (progress bars fill for 3s,
movement freezes, "reboot the show"), then ships the patch into the live world:
**new rooms appear, new books land on shelves, new text displays**. No restart,
no rebuild, no recompile. Applied patches are recorded in
`patches/applied.json` (ADD-only — never deleted, never applied twice).

## How it works
1. Game polls `patches/patch.json` every 8 seconds (env `MIND_PALACE_PATCHES_DIR`
   overrides the folder; default is `patches/` next to the game).
2. New `id` → 3s cinematic: **GAME PATCH LOADING** + title + ██░░░ progress bar.
3. Rooms in the patch are built into the world; books are added to their shelves;
   `texts` become in-world billboard messages after loading.
4. A toast shows for 10s: **PATCH <id> LOADED — <title>** with the first text.

## Ship a patch — exact commands (PowerShell, game running)
```powershell
# 1. Write your patch (see schema below) and save it anywhere, e.g.:
#    C:\patches\my-patch.json

# 2. Drop it into the game's patches folder:
powershell -ExecutionPolicy Bypass -File deploy_patch.ps1 -PatchFile C:\patches\my-patch.json -GameDir C:\path\to\mindpalace

# 3. Watch the game — within 8 seconds it shows GAME PATCH LOADING and the
#    new rooms/text appear. That's it.
```

## patch.json schema
```json
{
  "id": "2026-08-17-001",          // REQUIRED — must be new, never re-use
  "title": "Firefly Night",        // shown on the loading screen + toast
  "message": "The garden blooms.", // appended to texts, shown after load
  "texts": [                       // in-world messages after loading
    "Fireflies drift over the garden.",
    "The workshop hums."
  ],
  "rooms": [                       // new rooms, built live
    {"name": "Gardens", "desc": "A night garden.", "lang": "python"}
  ],
  "books": [                       // books placed on the new rooms' shelves
    {"title": "Patch Notes", "content": "Line one.\nLine two."}
  ]
}
```

## Rules (your doctrine, enforced)
- **ADD-only**: patches never delete or overwrite game content. A new room is
  always new. `applied.json` only grows.
- **Never re-use an `id`** — change it for every patch. Same id = ignored
  (already applied).
- If a room name already exists, it's added as a new room anyway (the palace
  can have two rooms with the same label — they're distinct).

## Verify it worked
```powershell
# applied.json shows the id — proof the patch shipped:
Get-Content patches\applied.json
# game console shows:
#   [Patch] NEW PATCH: 2026-08-17-001 — Firefly Night
#   [Patch] cinematic start: Firefly Night
#   [Patch] room added: Gardens
#   [Patch] applied: 2026-08-17-001 (1 rooms, 1 books, 2 texts)
```

## The demo patch
A sample `patches/patch.json` ships with the repo ("First Patch", 2 rooms,
1 book, 2 texts). The FIRST time you run the game it plays the cinematic and
builds them — proof the whole loop works. Delete the file after it's applied
if you don't want it to linger (it won't re-apply; the id is recorded).

## Where this lives in code
- `deploy/PatchManager.java` — poll, parse (no JSON deps), apply, applied.json
- `engine/GameEngine.java` — 8s poll, 3s cinematic, freeze during load, toast
- `render/FontRenderer.java` — atlas covers █ ░ box glyphs for the progress bar
