# Tailscale Foundation (T1) — com.mindpalace.tailscale

Plan: `.hermes/plans/2026-09-30_tailscale_mesh_and_depth_pass.md` (T1 card,
sources: plan uses 2, 4, 19). Precursor: MP-046 ContextBroadcaster proved the
pattern (tailnet bind + WhoIs identity + tag allowlists, fail-closed). T1
promotes that pattern into a **reusable foundation** so T2 (quorum vote
transport) and T3 (admin API + spectator pages) mount on the same invariants
instead of re-deriving them.

## Invariants (enforced here, nowhere else)

1. **Bind, never Funnel.** Private services bind the node's 100.x address via
   `TailnetTransport.onTailnet(...)`. No `tailscale funnel`, no WAN-listening
   port. `forLoopbackTest(port, ...)` exists for hermetic selftests only.
2. **Identity from the mesh, not the request.** Every exchange is resolved
   through `WhoIsResolver` (`tailscale whois --json <ip>`) and authorized
   with `TailnetRoles.capabilities(...)` before route logic runs. Failures
   are 403 with no detail. A peer with no identity is never authorized.
3. **Role gates per route.** Routes declare a minimum capability
   (`get` READ_PUBLIC / `getPrivate` READ_PRIVATE / `vote` VOTE /
   `build` BUILD / `admin` ADMIN). Spectators read; only architect-tagged or
   architect-allowlisted peers manage the world.

## Pieces

| Class | Role |
| --- | --- |
| `TailscaleCli` | Only place the game shells out to `tailscale` (ip, status, whois, dns status; 3s timeout; `MP_TAILSCALE_BIN` / `-Dmindpalace.tailscale.bin` override). Read-only. |
| `StatusSnapshot` | Parsed `tailscale status --json`: self + peer map by host/IP, MagicDNS suffix. |
| `MagicDns` | Hostname/FQDN validation (use 19), `fqdn()` / `hostPart()` for deep links. |
| `WhoIsResolver` | IP -> `TailnetIdentity` (use 2). `parseWhoIs` is the canonical parser; `Cli` impl caches 60s. |
| `TailnetIdentity` | record(login, deviceName, tags). Immutable, fail-closed when empty. |
| `TailnetRoles` | Tag -> capability mapping (use 4) + `Scope` allowlist bundle. |
| `TailnetTransport` | HTTP server factory (loopback or tailnet bind) with WhoIs+capability gate per route. |

## Scope model (TailnetRoles.Scope)

- `tag:architect` -> READ_PUBLIC, READ_PRIVATE, VOTE, BUILD, ADMIN
- `tag:builder`   -> READ_PUBLIC, READ_PRIVATE, BUILD
- `tag:voter`     -> READ_PUBLIC, VOTE
- `tag:spectator` -> READ_PUBLIC
- Untagged peers: only via `allowUsers`; full architect caps additionally
  require `architectLogins`.
- Defaults from `-Dmindpalace.tailscale.allow.tags` / `MP_TAILSCALE_ALLOW_TAGS`
  (all four role tags), `-Dmindpalace.tailscale.allow.users` /
  `MP_TAILSCALE_ALLOW_USERS`, `-Dmindpalace.tailscale.architect.login` /
  `MP_TAILSCALE_ARCHITECT_LOGIN`.
- Fail-closed: unknown peer, unknown tag, or any failure => zero capabilities.

## Wiring

- Selftest: `GameEngine.testTailnetFoundation()` — hermetic (stub resolver,
  loopback transport, no subprocess). Reports as
  `PASS tailscale foundation (roles, MagicDNS, WhoIs, transport gates)`.
- A tailnet-bound server is **opt-in at runtime**: T2/T3 call
  `TailnetTransport.onTailnet(resolver, scope, router)`; when Tailscale is
  off or absent the call throws and callers degrade gracefully (feature-gated,
  same approach as ContextBroadcaster).

## Verification

```
mvn clean compile
java -jar target/mindpalace-*.jar --selftest   # tailscale foundation PASS
bash e2e.sh
```

Live smoke (no game needed): `tailscale whois --json $(tailscale ip -4)` — the
payload `WhoIsResolver.parseWhoIs` accepts.