# Context broadcaster

The context broadcaster serves precomputed SHUTTLE, card, and triplet JSON
files to authorized devices on the Tailscale network. It is disabled by
default, does not run inference, and is not started in demo or self-test mode.

## Enable

Install Tailscale and make the `tailscale` CLI available on `PATH`, then set:

| Environment variable | Purpose | Default |
|---|---|---|
| `MP_CONTEXT_BROADCAST_ENABLED` | Set to `true` to enable the service | `false` |
| `MP_CONTEXT_DIR` | Directory containing the three artifact files | `%USERPROFILE%\AIGEN_SYS\context` (Windows) or `$HOME/AIGEN_SYS/context` |
| `MP_CONTEXT_PORT` | Port on the local Tailscale IPv4 address | `8765` |
| `MP_CONTEXT_ALLOWED_USERS` | Comma-separated exact Tailscale login names | none |
| `MP_CONTEXT_ALLOWED_TAGS` | Comma-separated exact Tailscale tags, such as `tag:context-reader` | none |
| `MP_TAILSCALE_BIN` | Tailscale executable if it is not on `PATH` | `tailscale` |

At least one allowed user or tag is required. The equivalent Java properties
are `mindpalace.context.enabled`, `mindpalace.context.dir`,
`mindpalace.context.port`, `mindpalace.context.allowedUsers`,
`mindpalace.context.allowedTags`, and `mindpalace.tailscale.bin`.
System properties take precedence over environment variables.

For example, in PowerShell:

```powershell
$env:MP_CONTEXT_BROADCAST_ENABLED = "true"
$env:MP_CONTEXT_ALLOWED_USERS = "alice@example.com"
$env:MP_CONTEXT_ALLOWED_TAGS = "tag:context-reader"
$env:MP_CONTEXT_DIR = "C:\Users\alice\AIGEN_SYS\context"
```

The process uses `tailscale ip -4` to select its bind address and runs
`tailscale whois --json <peer-ip>` for each request. Requests are authorized
only when the returned login or one of the returned node tags exactly matches
an allowlist entry. Missing or malformed WhoIs data is denied. The service
does not trust request-supplied identity headers and never binds to a wildcard
or LAN address.

## Files and endpoints

Place UTF-8 JSON files in the configured directory:

| File | Endpoint |
|---|---|
| `shuttle.json` | `GET /v1/shuttle` |
| `cards.json` | `GET /v1/cards` |
| `triplets.json` | `GET /v1/triplets` |

Files are read on each request, so a local producer can atomically replace an
artifact to publish an update without restarting MindPalace. Each response is
limited to 1 MB. Only these fixed routes are served; request paths cannot select
other files. The broadcaster does not create or modify artifacts, call a model,
or make outbound network requests other than local Tailscale CLI queries.
