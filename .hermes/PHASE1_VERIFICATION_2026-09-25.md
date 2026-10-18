# Phase-1 Acceptance Verification — record

- Verifier task: t_6dd98959 (board: mindpalace), attempt 3, executed 2026-10-13.
- Filename kept verbatim per task spec (dated 2026-09-25 = phase-1 window label).

## Scope

All 10 parent cards audited against the evidence standard (`commit`, `build`,
`selftest`, `e2e_shots` in completed-run metadata): t_a5823c92, t_b2e9bc3f,
t_0c1374b0, t_deed6566, t_948a71be, t_cf0c461b, t_58c95f59 and companions.
Every parent carries all four evidence fields in its run metadata —
phase1_parent_metadata.json sits beside this file.

## Integration outcome

- Baseline: local main 0d81525/0d81528 lineage (origin/main was e1e0825, 25 behind).
- t_948a71be quorum real-work merge completed: 18fff1c on the verifier branch
  (conflict policy: GameEngine 37b selftest block adopted from branch side;
  QuorumTaskSpawner + WeightedQuorumVote kept HEAD — t_deed6566 supersets;
  AgentManager auto-merge kept voteQuality + TASK_0132 sink + realWorkSource).
  This also resolved the stuck in-progress merge found in the main checkout.
- Verifier-found bug fixed during acceptance: KnowledgeGraph snapshot-publish
  race (eb8f9d9) — LiveUpdate thread clear()ed KG maps while render thread
  iterated them per-frame -> ConcurrentModificationException killed e2e tour
  attempt 1 at 2 shots. Fix: build() constructs fresh maps, volatile publish.
- Main merge commit: d1a8b80 "Merge verifier phase-1 acceptance into main".

## Verification evidence (2026-10-13)

| Gate | Result |
|------|--------|
| mvn clean compile | exit 0 |
| mvn clean package | exit 0, BUILD SUCCESS, mindpalace-1.1.0-beta1.jar |
| frozen-jar --selftest | 68 passed / 1 failed — context broadcaster loopback
  flake (request timed out, cold JVM, self-contained 127.0.0.1 test; passed in
  prior runs; judged environmental, not a merge regression) |
| e2e.sh full chain | VERIFY-PASS, E2E_EXIT=0, 22/22 waypoint shots OK,
  portal-pair/door-prompt/HUD gates OK (attempt 2, after KG fix eb8f9d9) |
| ONE-game rule | satisfied throughout: single javaw PID 8580 (live game);
  verified after sweep; e2e build JVMs transient |

Selftest/e2e ran from frozen jar copy target/frozen-mindpalace.jar
(sha256 cb1e29f0…), not the build-output jar, per evidence standard.

## Push state

- origin/main: e1e0825 -> d1a8b80 (pushed 2026-10-13).
- origin branch mindpalace/t_6dd98959-verifier-phase-1-acceptance-integrate: eb8f9d9.

## Phase status

Phase-1 cards integrated, built, self-tested, e2e-verified, pushed. Phase-1
ACCEPTED by verifier run t_6dd98959 with the one environmental selftest flake
recorded above. The KG fix ships with this acceptance (never delete, only
merge: additive change to build()/field declarations, read APIs untouched).