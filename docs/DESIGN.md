# DESIGN.md — design surfaces and how to shape them

> For designers. The palace's look is deliberately a re-skin system: every
> visual token lives in one place, so a designer can move the whole mood
> without touching game logic. Verification is functional until you approve
> the feel — aesthetics belong to you.

## Where the look lives

| Surface | File | What you control |
|---------|------|------------------|
| **Web personality** (re-skin layer) | `mind-palace` repo: `css/personality.css` (219 lines) | every color token in one `:root` block: void night, palace stone, cyan = public, pink = private, gold = TODO crystals; breathing crystals (3.4s), quorum flares (APPROVED = gold, REJECTED = ice-blue), glass panels, chiseled gold headings, two-tone agent chat, `prefers-reduced-motion` safe |
| 4D page + dynamic classes | `index-4d.html`, `js/4d-creator.js` | 34 shipped selectors; 7 live on static pages, rest live in 4D + creation forms |
| In-game palette | `Renderer.java` texture ids + vertex colors | neon family (cyan/pink/green/amber/red), hardwood, wallpaper, concrete — **no new textures** by budget rule; tint values only |
| HUD + prompts | `ui/HUD.java`, `ui/InteractionPromptSystem` | layout, text, timing of every on-screen prompt |
| Avatar | `ui/DressingRoom.java` + `avatar/AvatarLibrary` | presets, materials — Cortana preset is the start point |
| Loading screen | `GameEngine.renderLoadingFrame` | colors, text pacing |

## Design constraints (why they exist — argue before breaking)

1. **Re-skin only**: personality.css loads after base CSS; cascade order
   wins. Never re-lay-out — that's what keeps designer work merge-safe.
2. **No new textures**: the Intel HD 510 budget. Vertex-color + palette
   tints only; a texture upload is a perf regression by definition.
3. **Determinism**: any procedural visual (forest, portals) must produce
   identical frames boot-to-boot — the E2E tour depends on it.
4. **Reduced-motion respected**: every new animation gets a
   `prefers-reduced-motion` off-switch (personality.css already does).

## The three most clip-able moments (design targets)

1. **Rotor-clock music** — chord progression turns with the courtyard
   rings; carries voice as tick/chime/bell. A 70-second stand in the
   courtyard is a complete musical loop.
2. **Quorum gold flares** — APPROVED votes flare gold on the door frame
   heatmap (queued: step 72). Currently doors show activity tiers only.
3. **Agent chat two-tone** — Explorer/critic conversation rendered
   in-world; the never-twice + anti-loop directives make it read like
   characters, not logs.

## Open design asks (take one)

| # | Ask | Where |
|---|-----|-------|
| 1 | Quorum flare visual (gold pulse on APPROVED riding rotor carries) | step 72 + personality.css |
| 2 | Editor skin: syntax highlighting colors for the book editor | step 118, palette tokens |
| 3 | Loading screen: progress art between "Scanning repositories" and "World built" | renderLoadingFrame |
| 4 | Dressing room poses/wardrobe presets | AvatarLibrary |
| 5 | Web 4D page: creation-form polish | index-4d.html + personality.css |

Ship path for CSS work: edit `personality.css` → push `mind-palace` main →
verify on Pages (HTTP 200 + byte-check) → screenshot before/after in the
PR. Ship path for in-game work = the cascade (CONTRIBUTING.md).