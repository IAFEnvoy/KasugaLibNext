# Multiplexer vs FSM — which to use

Both live under `lib.kasuga.rendering.models.uml.dynamic` and both "pick a pose variant from conditions", so
it is not obvious which one a feature should reach for. They solve different problems.

## TL;DR

| Need | Use |
|---|---|
| "Pick one of N models based on the block's current state (powered? redstone level? tag?), no animation, switch instantly when the input changes" | **Multiplexer** |
| "Play a timed animation: states with durations, transitions with guards/triggers, cross-fade blending between states, parallel layers" | **FSM** |
| "A host block that already runs an FSM but whose *target model* depends on an external condition" | **Both** — the multiplexer selects the variant, then points the FSM's `PoseSink` at it |

## Multiplexer — stateless variant selector

`dynamic/multiplexer/` (`Multiplexer`, `MuxState`, `Variant`, `SelectorPredicates`, `Context`/`McContext`).

- **No time.** A `Variant` is a static pose/model; the multiplexer evaluates predicates and picks one each
  update. There is no duration, no transition, no interpolation.
- **No blending.** Switching variants is an instant model swap.
- **Input-driven.** The host evaluates the multiplexer on block update with a `Context` (block properties,
  neighbor state, redstone, tags) — conditions are pure predicates over that context. The host's custom
  extension channel is `Blackboard` (typed/raw keys on the `Context`).
- **One variant at a time**, chosen from a flat list; `SelectorPredicates` gives the common
  data-flag/data-equals builders.

Use it when the "state" is really just a function of the environment and there is nothing to animate —
e.g. a cable block whose model depends on its connections, a machine whose model depends on a tier tag.

## FSM — timed transition graph

`dynamic/fsm/` (`StateMachine`, `Layer`, `State`, `Transition`, `Blender`, the `state/` typed-var store).

- **Time is first-class.** States have `durationTicks`; transitions fire on `whenComplete`, on triggers, or on
  guards; cross-fades interpolate between states over real seconds.
- **Parallel layers** compose (BASE/ADDITIVE/OVERRIDE) into a blended pose each tick.
- **Typed value store** (`StateVar`) + conditions/actions let guards read/write state; data-driven JSON
  definitions + a script API drive it without recompiling.
- **Owner-generic** (`StateMachine<MyActor>`) so `ctx.owner()` is typed; a `PoseSink` is the single seam to
  the actual model (null sink = logic-only server).

Use it when motion matters — a windmill that spins up, a door that opens in stages, a character with a
locomotion/upper-body layer setup.

## Composing them

The multiplexer does not run an FSM and the FSM does not consult a multiplexer, but a host can use both: the
host evaluates its `Multiplexer` to pick a `Variant` (a model), then runs a `StateMachine` and points that
machine's `PoseSink` at the chosen variant's model. When the multiplexer picks a different variant, the host
re-points the sink; the FSM keeps its current state across the swap. This is the intended pattern when a
single animated machine can be skinned by several models selected by the environment.

## Out of scope (roadmap)

- **GUI driving** (`menu`/`screen` → `trigger` → state switch): the typed-var + trigger primitives are a
  general state-machine already, but there is no FSM × menu/screen wiring today. `MachineQuery.machineAt`
  (capability lookup of a host's machine) is the minimal entry point for a future GUI binding.
