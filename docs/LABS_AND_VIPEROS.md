# Science Labs, Computers and ViperOS

Status: design (cards LAB-0..LAB-9 on the `mindpalace` board). Nothing here is built yet.

Today a "lab" is a glowing `LabDevice` cube whose colour is *guessed from a test file name*
(`LabDevice.guessStatus`). That is decoration. This document makes labs real, adds clickable
computers, and starts the ViperOS (Rust, Redox-based) track.

## 1. Principles

1. **Sandbox first, hardware never by default.** Every lab runs against a *simulated* world.
   Real radios, serial ports, USB and the LAN are reachable only through an explicit, logged,
   owner-approved bridge (section 6).
2. **Local only.** No cloud inference, no outbound internet from a sandbox. Tailnet traffic is
   limited to the owner's login (see `TAILSCALE_FOUNDATION.md`).
3. **Deterministic and replayable.** A lab run is `(seed, scenario, code) -> trace`. The same
   inputs give the same trace, so A/B results are comparable and agents can be audited.
4. **Hard resource caps.** CPU time, memory, output size, wall clock and file count per run.
   A runaway experiment must not cost the live game a frame budget (35-47 FPS today).
5. **Nothing for the player to do.** Agents run experiments on their own; the player may watch,
   click in, or ignore it.

## 2. Architecture

```
 Lab (world object)  ->  LabRunner  ->  SandboxBackend  ->  SimWorld  ->  Trace + Metrics
                            |               |                  |
                       ABEngine         (Script | Wasm |    (radio, net, bus,
                       Scoreboard        QemuVM)            clock, disk)
 Computer (world object) -> OsOverlay -> ViperOS guest (QEMU, no host share)
```

- `LabRunner`: queue, caps, seeds, result store (`labs/<id>/runs/<n>/`), kill switch.
- `SandboxBackend` has three tiers, chosen per lab:
  - **Script**: a restricted interpreter (reuse the existing AstGate allow-list approach).
  - **Wasm**: compiled module with fuel metering, no WASI file or socket access.
  - **QemuVM**: ViperOS guest, user-mode networking off, virtual NIC wired only to `SimWorld`.
- `SimWorld`: virtual clock, virtual links (loss, latency, jitter, airtime), virtual radios.
- `ABEngine`: runs variant A and B on identical seeds, reports effect size and a confidence
  interval, refuses to call a winner below the sample floor.

## 3. The 50 functions

### A. Experiment core (1-10)
1. Create lab from template (network, firmware, protocol, AI-behaviour).
2. Seeded deterministic run with replay.
3. Hard caps: CPU, RAM, wall clock, output bytes, file count.
4. Snapshot and restore of a whole sandbox.
5. Fork a run (branch from any tick).
6. Trace recorder (events, packets, syscalls) with compact binary format.
7. Metrics registry (counters, gauges, histograms) per run.
8. Run queue with fair scheduling across agents, one heavy lab at a time.
9. Kill switch and watchdog (per run, per lab, global).
10. Result archive with retention policy and backup inclusion.

### B. A/B and statistics (11-20)
11. A/B runner on paired seeds.
12. A/B/n with multiple-comparison correction.
13. Effect size and bootstrap confidence intervals.
14. Sample-size floor and "no decision yet" verdict.
15. Sequential testing (stop early only with a valid boundary).
16. Parameter sweep (grid, random, Latin hypercube).
17. Regression guard: nightly rerun of pinned baselines.
18. Flaky-test detector (rerun on N seeds, report variance).
19. Leaderboard of variants per scenario.
20. Auto-promote winner to a proposal for quorum vote (never auto-merge).

### C. Network and radio simulation (21-32)
21. Virtual links with loss, latency, jitter, bandwidth.
22. Topology editor (star, mesh, tree, line) and mobility models.
23. Packet capture viewer with filters (pcap export).
24. Fault injection: partition, node crash, clock skew, packet corruption.
25. Protocol fuzzer (structure-aware) with crash corpus.
26. TCP/UDP/QUIC-style stack scenarios for congestion experiments.
27. **LoRa PHY model**: spreading factor 7-12, bandwidth, coding rate, time on air, receiver
    sensitivity, capture effect, co-channel and inter-SF interference.
28. **LoRaWAN MAC**: OTAA and ABP join, MIC and AES-128 session keys, FCnt checks, MAC commands.
29. **Adaptive data rate**, duty-cycle limits and regional plans (EU868, US915, AS923, AU915).
30. Device classes A, B (beacon slots) and C (continuous receive) with downlink scheduling.
31. Gateway and network-server model (multi-gateway dedup, ADR, downlink queue).
32. Path-loss and terrain models (free space, log-distance, simple building loss).

### D. Hardware and embedded (33-40)
33. Virtual MCU targets (Cortex-M style, RISC-V) with peripherals: GPIO, UART, SPI, I2C, ADC.
34. Firmware image loader with signature check inside the sandbox.
35. Virtual sensors (temperature, GPS, accelerometer) with scripted and noisy signals.
36. Power and battery model (sleep current, TX cost) for lifetime experiments.
37. Logic analyser and scope view for virtual buses.
38. Watchdog, brown-out and flash-wear simulation.
39. **Optional real-hardware bridge** (serial, USB, SDR dongle, real LoRa gateway via
    ChirpStack) behind the section 6 gate.
40. Hardware-in-the-loop record and replay: capture a real trace once, re-run it in simulation.

### E. Security and hardening (41-45)
41. Capability manifest per lab (what it may touch), default deny.
42. Egress firewall inside the sandbox: only `SimWorld` addresses exist.
43. Static analysis gate on submitted code before it runs (extends AstGate).
44. Audit log (who ran what, with which caps), append-only and hashed in a chain.
45. Secrets hygiene: LoRaWAN keys are generated per run and never written to world files or logs.

### F. Agents and game integration (46-50)
46. NPC researchers pick experiments by curiosity and BDI intent; results feed their memory.
47. Lab results unlock game content (new rooms, items, agent skills).
48. Mistral writes the lab notebook entry and a short story for notable results.
49. Spectator mode: camera follows a packet or a node through the simulation.
50. Shareable experiment bundle (scenario + seed + code + trace) for other tailnet nodes.

## 4. Computers and the OS overlay

- Every computer block is clickable. Click opens a full-screen **OS overlay** that pauses nothing
  in the world but captures keyboard focus until `Esc` (single Esc closes the overlay; the
  global Esc x2 exit rule is unchanged and takes precedence only when no overlay is open).
- The overlay renders the guest framebuffer to a GL texture, with a serial console fallback.
- Input is forwarded only while the overlay has focus. The guest never sees the host keyboard
  layout, clipboard or files.
- Each computer owns an **immutable base image plus a copy-on-write overlay disk**; resetting a
  computer is deleting the overlay.
- Computers can be networked to labs inside `SimWorld`: a computer is how an agent or player
  writes code, flashes a virtual MCU or runs a gateway.

## 5. ViperOS: hardened start on Redox

ViperOS is a Rust microkernel userland based on Redox OS. First milestones are about *being
safe to run*, not about features.

| Step | Goal | Done when |
|------|------|-----------|
| V0 | Reproducible Redox build in CI, pinned toolchain and sources, image hash recorded | Same hash on two builds |
| V1 | Boots under QEMU (x86_64), serial console, headless smoke test | Boot to shell in < 30 s, scripted |
| V2 | Hardened defaults: no network daemons, no default user password, read-only root, minimal scheme set (namespaces), no shared folders | Checklist passes in a boot test |
| V3 | Guest-agent channel: framebuffer + keyboard + a tiny virtio-serial RPC (run, stat, copy-in/out of an explicit exchange dir) | Overlay shows the guest |
| V4 | `SimWorld` NIC: guest sees only simulated links; LoRaWAN tools (`lorawan-cli`, packet forwarder stub) run in guest | Join + uplink demo in lab |
| V5 | Signed images, verified at launch, tamper test | Modified image refuses to boot |
| V6 | Fuzz the guest-agent RPC and the framebuffer path (cargo-fuzz) | 24 h clean run |

Risks to state plainly: Redox is young and has limited driver and hardware support, so the QEMU
path is the supported one. Do not claim "super hardened" until V2 and V5 pass and V6 is clean;
report each as it lands.

## 6. Real hardware and network bridge (gated)

Off by default. To enable a device: owner login must be present (Tailscale `Self` login), the
device path is on an allowlist file, the lab manifest names it, and every session is audit-logged.
LoRa transmissions on real hardware must respect the regional duty cycle and use test frequencies
or a shielded setup. The simulator enforces duty cycle; the bridge enforces it again independently.

## 7. A/B testing in practice

Each lab scenario declares: hypothesis, metric, variants, seed count, stop rule. Examples:
- ADR on versus off: delivery ratio and energy per delivered packet at 200 nodes.
- Spreading-factor assignment policy: airtime fairness under load.
- Agent behaviour: BDI-FSM variant A versus B on mission completion time.
- Dispatcher weights (ties into `DISPATCHER_HESSIAN_BDI.md`): replay logged tasks offline.

## 8. Delivery order

LAB-0 spec + `LabRunner` skeleton -> LAB-1 SimWorld + virtual links -> LAB-2 ABEngine ->
LAB-3 LoRa PHY/MAC -> LAB-4 Script/Wasm sandbox + caps -> LAB-5 Computer click + OS overlay (stub
terminal) -> LAB-6 ViperOS V0-V2 -> LAB-7 guest bridge V3-V4 -> LAB-8 hardware bridge (gated) ->
LAB-9 agent integration + content hooks.
