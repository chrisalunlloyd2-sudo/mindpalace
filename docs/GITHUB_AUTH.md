# GitHub auth — `MIND_PALACE_AGENT_TOKEN` only

MindPalace talks to the GitHub REST API with a **repo-scoped token supplied by
you through one environment variable**. Nothing else is consulted.

```
setx MIND_PALACE_AGENT_TOKEN "github_pat_..."     # Windows, persists for new shells
```

Or for a single session (git-bash):

```
MIND_PALACE_AGENT_TOKEN=github_pat_... java -jar mindpalace-live.jar
```

## What happens at boot

1. `GitHubClient.loadAgentToken()` reads `MIND_PALACE_AGENT_TOKEN`
   (`GitHubClient.AGENT_TOKEN_ENV`).
2. Token present → `[GitHub] Authenticated (agent token)`; repos, files and
   issues are live.
3. Token **unset** → the game runs **unauthenticated**: demo fixtures only,
   `--demo` behaviour, no API calls. This is the fail-soft path, not an error.

## What is deliberately NOT used

- **No Windows Credential Manager.** The Credential-Manager PAT path was
  removed in #333 (commit 9a1b04c). The agent fleet must never hold the
  owner's personal Windows login; `loadAgentToken()` has no git or
  credential-manager fallback.
- **No hardcoded tokens** in files, configs or jars — ever.

## Token scope

A **fine-grained** token scoped to the repositories the world should show
(`Contents: read/write` for CRUD, `Issues: read/write` for the issue stream).
Prefer a short expiry and rotate it; classic PATs also work but carry more
privilege than needed.

## Autonomous writes

Agent tool-loop writes never land on the default branch: they open an
`agent/yyyyMMdd` branch (fail-closed) and a person merges — `AgentWriteRules`
gates reads/writes deny-by-default (#287/#321). That is policy, enforced in
code, independent of the token's own scope.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `[GitHub] no agent token — running unauthenticated` | `MIND_PALACE_AGENT_TOKEN` not set in the launching shell/env |
| `IssueStream raise failed: 401` | token expired or lacks `Issues` permission — mint a new one |
| Private repos missing from world | the token's account must have access; private rooms render pink |
| Everything empty after a Windows update | the env var is per-user, per-session — `setx` again or set it in the launching script |
