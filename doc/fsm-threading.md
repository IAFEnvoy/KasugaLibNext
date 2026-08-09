# FSM threading model

Who may touch what, from which thread. The FSM is touched by three actors: the **server tick thread**
(per-dimension `MinecraftServer`), the **client main thread** (render + client tick), and a **script engine
thread** (Javet/V8, for script-owned machines). Host-owned block-entity machines live on their side's tick
thread; script-owned machines live on the script engine thread that drives them.

## Per-component contract

| Component | Thread contract |
|---|---|
| `StateMachine.tick` / `Layer.tick` / `advancePuppet` | Single thread per machine — the host tick thread (server or client) or the script thread that owns it. Never called concurrently across threads. |
| `StateMachine.vars()` / `MutableStateMap` | **Not thread-safe** (bare `HashMap`). Confined to the machine's owning thread: server BE → server tick; script machine → script thread. Cross-thread reads (e.g. a `MachineQuery` capability lookup from another thread) must copy/snapshot, not read live. |
| `MutableStateMap.removeEphemeral()` | Runs at the end of `tick` on the owning thread. Puppet (`logicEnabled=false`) machines skip it. |
| `FsmDefinitions` / `FsmMachines` (via `FsmRegistries.GLOBAL`) | `ConcurrentHashMap`-backed; registration/lookup safe from any thread. Host machines are **not** registered in `FsmMachines` (only script machines); hosts hold their own `StateMachine` reference. |
| `StateVarRegistry` / `FsmFunctionLibrary` (via `FsmRegistries.GLOBAL`) | `ConcurrentHashMap`-backed; registration during mod construction / resource reload / script load; read during machine build + tick. |
| `FsmSyncServer.GLOBAL` (dedup/heartbeat) | `ConcurrentHashMap`-backed; pushed from the server tick thread, cleaned on player logout. |
| `FsmSyncClient.INSTANCE` | All mutation (`apply`/`bind`/`unbind`) runs on the **client main thread** — the `FsmSyncChannel` handler wraps `apply` in `context.enqueueWork(...)`. |
| `AnimationBlockEntity.machine()` | `synchronized` (lazy build) + the `machine` field is `volatile` so the bare reads in `tickClient`/`machineVersion`/`ensureClientModel` see a consistent reference. In practice the BE is only touched from its side's tick thread. |
| `KasugaModelPipelines` | Client main thread only (client pipelines). Server-safe no-op when pipelines are not initialised. |

## Client vs server (puppet mode)

A host-owned machine exists on **both** sides:

- **Server** machine (`logicEnabled=true`, sink `null`): authoritative — evaluates transitions, runs actions,
  bumps `version`, pushes snapshots via `FsmSyncServer`.
- **Client** machine (`logicEnabled=false`, sink = `ModelInstancePoseSink`): **puppet** — does NOT evaluate
  transitions or run actions (it does not sync `vars`, so guards would diverge). It only smooth-interpolates
  the in-flight cross-fade between server snapshots (`conform(StateMachineSnapshot)` supplies the active
  transition + elapsed seconds).

This is why `vars` are intentionally not in the sync payload: the client never reads them to evaluate guards.

## Script machines

A script-owned machine (`AnimatorBuilder.instantiate`) is registered in `FsmRegistries.GLOBAL.machines()` and ticked by
the script via `Animator.tick(handle)` on the script engine thread. It must be ticked from a single thread; it
has no render/sync channel (it is server-side logic only — there is no `FsmSyncServer` push and no client
mirror). Script `Animator.get/set/trigger` calls resolve against the registry and read/write the machine's
`vars` on the script thread.
