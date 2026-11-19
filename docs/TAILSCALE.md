# Tailscale in MindPalace — the private mesh

The palace's networking principle: every multiplayer, agent, quorum and
spectator feature binds to the tailnet (WireGuard mesh between the
Architect's own nodes), identity is resolved from the mesh (WhoIs), and
rights come from tailnet tags. **No ports on the public internet. No
Funnel. No cloud inference** — the mesh only ever carries traffic between
the Architect's own machines, and model calls stay local
(`OLLAMA_NO_CLOUD=1`).

Status and inventory of the code today lives in
[TAILSCALE_FOUNDATION.md](TAILSCALE_FOUNDATION.md) (`com.mindpalace.tailscale`:
TailscaleCli, StatusSnapshot, MagicDns, WhoIsResolver, TailnetIdentity,
TailnetRoles, TailnetTransport). The context broadcaster (MP-046 / #191)
already proved the pattern in production: tailnet bind + WhoIs identity +
tag allowlists, fail-closed.

## The twenty uses

Transport principle per use: the service binds to the node's `100.x`
address (or `tailscale serve`), WhoIs gives the identity, ACL tags decide
rights. Numbering matches plan
`2026-09-30_tailscale_mesh_and_depth_pass.md` §1.

| # | Use | How / why it fits |
|---|-----|-------------------|
| 1 | **Private multiplayer transport** (Phase 8/G) | Clients join `mindpalace-host` over the tailnet; no NAT traversal, no port forwarding, no exposed UDP. |
| 2 | **Identity = tailnet user** | `tailscale whois --json <ip>` per connection (via `WhoIsResolver`) gives login + device; no passwords; avatar auto-named. |
| 3 | **Quorum voting over the mesh** | Each node is a voter; votes are attributable to a real machine because they ride authenticated tailnet identity. |
| 4 | **ACL tags as game roles** (`tag:architect`, `tag:builder`, `tag:voter`, `tag:spectator`) | Permissions (build / vote / watch) enforced by `TailnetRoles.capabilities(...)` from network policy, not game code alone. |
| 5 | **Remote world control** | Architect drives world settings (spawn, weather, rooms) from phone/laptop through an admin-capability-gated API (T3 epic). |
| 6 | **Distributed local-model swarm** | `ModelRouter` reaches Ollama on whichever node hosts the model at its `100.x` endpoint — still 100% private inference, no cloud fallback. |
| 7 | **Remote build farm** | The game compiles/tests agent-written programs on a beefier node; results stream back to the tools loop (BUILD capability). |
| 8 | **Hermes across machines** | Kanban workers and gateways on different boxes share one board; a second node picks up cards when this one is asleep. |
| 9 | **Live HUD on your phone** | Read-only spectator page (`tailscale serve`, READ_PUBLIC) showing the palace map, open cards and votes. |
| 10 | **Cross-node knowledge-graph sync** | Tree of Knowledge replicates between nodes; conflict resolution by timestamp + node tag. |
| 11 | **Log/telemetry collector** | Every node ships game logs to one collector on the tailnet, feeding the Tree of Knowledge audit (MP-048 / #192). |
| 12 | **Taildrop asset exchange** | Posters, LoRA adapters, save files, screenshots move between nodes with `tailscale file cp`. |
| 13 | **SSH as a game mechanic** | Tailscale SSH lets a room "open a terminal" on another node; the door is a real shell session with session recording. |
| 14 | **Shared dev environments** | Pair-programming: a teammate joins your room and sees the same editor; each edit becomes a KG node. |
| 15 | **Backup pipeline off-site** | Backup (`C:`→`D:` today) mirrors to a second node; restore tests run against the replica (MP-041 / #186). |
| 16 | **Outside-world subnet router** | Phase G: expose a LAN device (printer, sensors, home server) as an "outside world" room. |
| 17 | **Exit node for the Tree-of-Knowledge crawler** | The web crawler leaves through a chosen exit node; rate limiting + clean IP, catalogued by Squigly. |
| 18 | **Tailnet lock + device approval as anti-cheat** | Only approved devices can vote/build; a rogue node cannot join the quorum. |
| 19 | **MagicDNS room addresses** | Rooms/worlds get stable names via `MagicDns.fqdn()` (`palace.tailnet.ts.net/room/12`) — shareable deep links. |
| 20 | **Tailnet-per-world isolation** | Each world (or community hive) is its own tailnet/tag set: invite by auth key, clean isolation (Headscale-compatible). |

## Current state (verified 2026-11-19)

- Tailscale installed and logged in on this machine
  (`desktop-46k80qd` = `100.77.137.74`); hub node `viper-main`
  (`100.126.6.101`) online — the plan's 2026-09-30 "not installed" finding
  is resolved.
- Shipped code: the reusable foundation above (selftest check
  `tailscale foundation`), the ContextBroadcaster (tailnet-bound,
  WhoIs-gated), and `TailscaleCli` as the single shell-out point
  (`MP_TAILSCALE_BIN` overrides the binary path).
- Not yet built: quorum vote transport over the mesh (T2), admin API +
  spectator pages (T3), the Architect-provided mesh scripts
  (git-credential-tailscale, tailscale_todo_sync.py, ragnet_mesh.py —
  T4), and the multiplayer phases that ride the mesh (Phase 8).
- Hard rules unchanged: bind, never Funnel; identity from the mesh;
  role gates per route; fail-closed on every unknown peer.

*Sources: `.hermes/plans/2026-09-30_tailscale_mesh_and_depth_pass.md`
(Architect-approved), `docs/TAILSCALE_FOUNDATION.md`, live
`tailscale status`. Public-sourced suggestions still enter through the
normal triage gate; this document is Architect-authored.*
