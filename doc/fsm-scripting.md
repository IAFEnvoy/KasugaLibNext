# FSM scripting (JS)

The FSM is a data-driven animation state machine (see `doc/fsm-design-review.md` for the full design).
From JavaScript it has two faces, registered as engine globals on every Javet engine:

- **`AnimatorBuilder`** — the *build* surface: register definitions, state vars, guard/action callbacks,
  and instantiate machines. Throws on authoring errors (a JS `try/catch` can catch and the engine stays
  alive).
- **`Animator`** — the *control* surface: drive a machine instance by handle (tick, read/write vars,
  trigger, switch state). Never throws across the boundary — logs once and no-ops on bad input.

```js
// registers under "test:my_machine" and returns the id
AnimatorBuilder.registerDefinition({
  id: "test:my_machine",
  state_vars: [
    { name: "speed", type: "float", default: 0.0 },
    { name: "jump",  type: "bool",  default: false, ephemeral: true }
  ],
  layers: [{
    id: "main", initial_state: "idle",
    states: [
      { id: "idle" },
      { id: "walk", duration_ticks: 10 },
      { id: "air",  duration_ticks: 5 }
    ],
    transitions: [
      { id: "i2w", from: "idle", to: "walk", when: ["test:is_walking"] },
      { id: "w2i", from: "walk", to: "idle", when_complete: true },
      { id: "i2a", from: "idle", to: "air",  trigger_on: "jump" },
      { id: "a2i", from: "air",  to: "idle", when_complete: true }
    ]
  }]
});

// JS guards/actions — the main scenario. ctx is a ScriptFsmContext (see below).
AnimatorBuilder.registerCondition("test", "is_walking", ctx => {
  const owner = ctx.owner();
  return owner != null && owner.speed > 0;
});

const owner = { speed: 0 };
const h = AnimatorBuilder.instantiate("test:my_machine", owner);

Animator.autoTick(h, true);     // driven each server tick — no timer boilerplate
// ...or drive manually: Animator.tick(h);

owner.speed = 3;
// Animator.set(h, "speed", 3);  // alternatively, write via the typed var
```

## Building (`AnimatorBuilder`)

| Method | Notes |
|---|---|
| `registerDefinition(objOrJsonString)` | Accepts a JS object literal (`{id:...}`) **or** a `JSON.stringify`'d string. Returns the definition id. |
| `registerStateVar(ns, path, type, default?, ephemeral?)` | Declares a typed var. `type` ∈ `AnimatorBuilder.varTypes()` (`bool`/`int`/`float`/`string`/`resource`/`vec3`). Returns the full id `ns:path`. |
| `registerCondition(ns, path, fn)` | JS guard: `ctx => boolean`. Stored under `ns:path`, referenced from `when: [...]`. |
| `registerAction(ns, path, fn)` | JS action: `ctx => {...}`. Referenced from `on_enter`/`on_exit`/`on_fire`. |
| `instantiate(id, owner?, model?)` | Builds a runtime machine; returns an **int handle**. `owner` is your actor (any JS object — identity is preserved, so guards see live mutations). `model` (a `ModelInstance`) attaches a render sink. |
| `varTypes()` | The allowed type tokens. |

**Order matters:** register conditions/actions/vars **before** `registerDefinition`/`instantiate`. A missing
guard/action is logged and degrades (guard → `false`, action → no-op, transition skipped) — it does not throw.

## Driving (`Animator`)

All methods take the int handle. **Var ids can be the full id (`"test:my_machine/speed"`) or — since the
handle knows its definition — the short name (`"speed"`)**; short names must be unambiguous within the machine.

| Method | Notes |
|---|---|
| `get(h, varId)` / `set(h, varId, value)` / `has(h, varId)` | Typed var read/write. Floats arrive as JS numbers, vec3 as `[x,y,z]`. |
| `trigger(h, triggerId)` / `isTriggered(h, triggerId)` | Tick-scoped ephemeral boolean triggers. |
| `goTo(h, layerId, stateId)` | Imperative instant switch. |
| `tick(h)` / `tick(h, dtSeconds)` | Advance one tick (1/20s) or by `dt` seconds. |
| `autoTick(h, on)` | Toggle per-server-tick auto-advancement (driven on the owning script thread). |
| `onTick(h, callback)` | Auto-advance **and** run the no-arg `callback` after each tick. |
| `onStateChanged(h, callback)` | Auto-advance **and** run the no-arg `callback` only when the active state changes (version bump) — the efficient alternative to polling `getState` every tick. |
| `dispose(h)` | Release the handle + stop auto-tick/onTick. |
| `getState(h, layerId)` / `getLayerMode` / `getLayerWeight` / `isLayerLocked` / `getStateElapsed` / `getStateDuration` / `getTick(h)` / `getVersion(h)` | Structural reads. `getTick`/`getLayerWeight` return JS Numbers (not BigInt). |
| `listVars(h)` | Ids of vars with an explicitly-set value. |
| `declaredVars(h)` | Ids of all vars the machine's definition declares (the discoverable set). |
| `varType(varId)` | The type token of a var. |

## The guard/action context (`ctx`)

A guard/action callback receives a `ctx` (a `ScriptFsmContext`) — the only handle a JS guard has into the
FSM and the owner. It is a thin `@Api` adapter over the real (modelling-side) `StateContext`:

- `ctx.owner()` — the actor object passed to `instantiate`. **Live identity:** JS-side mutations stay visible.
- `ctx.get(varId)` / `ctx.set(varId, value)` / `ctx.has(varId)` — typed var access by id.
- `ctx.trigger(triggerId)` / `ctx.isTriggered(triggerId)`.
- `ctx.isClientSide()`, `ctx.stateElapsedTicks()`, `ctx.stateDurationTicks()`, `ctx.isLayerLocked()`.

## Var id convention

Inline vars get the id `<machineId>/<shortName>` — e.g. var `speed` on `test:my_machine` →
`test:my_machine/speed`. Inside JSON you write the short name (`"speed"`); from JS control code you can use
either the full id or the short name (the handle resolves the short name against the machine's declared vars).

## `require`

Production engines resolve three forms:

- `require("kasuga:timer")` — the built-in timer module (`setInterval`/`setTimeout`/`clearInterval`).
- `require("./utils")` / `require("../utils")` — in-package relative modules, scoped to the requiring
  module's own package (so two packages each having `utils.js` don't collide). Resolves `x.js` then
  `x/index.js`.
- `require("@scope/pkg")` — another package by name (its main entry).

## Threading & lifecycle

- Each scripting machine is **single-threaded**: tick it from one thread only. `autoTick`/`onTick` run on
  the owning script thread (one per package), so they honor that invariant — never tick the same handle from
  two engines.
- Host-owned machines (block entities/entities) are ticked by their host and are **not** in the scripting
  machine registry; don't mix the two tick paths.
- Script hot-reload rebuilds the engine: JS state (handles, closures, timers, onTick callbacks) is lost —
  rebuild everything in the entry. Pinned JS callbacks are freed when the engine closes.

## Build note

This project builds under **JDK 21** (GraalVM 21 works), not the system-default JDK 25 — the gradle plugin's
`compileGroovy` fails on class file version 69 under JDK 25. Set
`JAVA_HOME=$(/usr/libexec/java_home -v 21)` before `./gradlew`. See also the FSM threading model
(`doc/fsm-threading.md`) and the multiplexer-vs-FSM contrast (`doc/multiplexer-vs-fsm.md`).

## Roadmap (round-3 follow-ups)

These round-3 items are documented but not yet implemented:

- **Script error visibility** — a client error screen / command feedback for script syntax/runtime errors
  (the engine already logs them; there's no in-game surface analogous to the client's missing-engine error
  page). The `ScriptEngineMissingScreen` pattern is the existing reference.
- **GUI binding** — driving an FSM from a menu (button → `Animator.trigger`/`goTo`). The control surface
  exists; the menu integration does not.

