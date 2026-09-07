# GitHub Auth — how the palace signs in

MindPalace never asks you to paste a token anywhere. The world is built
from your real GitHub account, read through a Personal Access Token (PAT)
that is loaded automatically from Windows Credential Manager.

## How it works (automatic)

1. You have already pushed with git at least once → Credential Manager
   holds a github.com PAT.
2. At boot, `GitHubClient.loadTokenFromCredentialManager()` runs
   `git credential-manager get` (no shell, stdin-fed), grabs the
   `password=` line, sanitizes it, and authenticates.
3. Console confirms: `[GitHub] Authenticated via Windows Credential Manager`.

No env vars, no config files, no tokens in the repo — ever.

## First-time setup (fresh machine)

If Credential Manager has no github.com entry yet, create one the normal
git way — the game reuses it:

    git ls-remote https://github.com/youruser/anyrepo.git

(credential-manager prompts once, stores the PAT, done), or explicitly:

    git credential-manager github login

## PAT scope

The token needs `repo` scope (contents CRUD + issues). Classic PAT works;
fine-grained tokens work if they include Contents + Issues read/write.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `[GitHub] Failed to load token` | no github.com credential stored — run any `git ls-remote` against github.com once |
| `IssueStream raise failed: 401` | PAT expired or lacks `repo` scope — re-login as above |
| Private repos missing from world | the PAT's user must have access; private repos render pink |
| Works in game, `gh` CLI not | separate credential: `gh auth login` or `GH_TOKEN` env var |

## Security notes

- Token lives in OS credential storage, loaded per-boot, never logged.
- The agents' file writes go through the same token — the push-gate
  (step 96) will require quorum approval on diffs before autonomous pushes.
- The issue stream is ADD-ONLY: agents can raise issues, never close,
  edit, or delete anything.
