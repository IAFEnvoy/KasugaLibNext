package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.data.Blackboard;

import java.util.function.Consumer;

/**
 * The typed context handed to {@link Transition} guards and {@link State} callbacks. Fully generic
 * over {@link Owner}, so {@code ctx.owner()} returns the actor with no cast (type-safe). Carries the
 * imperative surface: {@code goTo/trigger/lockLayer/signal}.
 *
 * <p>It also implements {@link StateReader}, so conditions and actions can read collaborative state
 * through a single path API: {@code ctx.read("data.combo", int.class)},
 * {@code ctx.read("layer.state")}, {@code ctx.read("trigger.attack")}.
 */
public record StateContext<Owner>(
        StateMachine<Owner> machine,
        Layer<Owner> layer,
        State<Owner> state,
        long tick
) implements StateReader {

    /**
     * The owning actor — fully typed. Read its fields directly in conditions: {@code ctx -> ctx.owner().moving}.
     */
    public Owner owner() {return machine.owner();}

    public boolean isClientSide() {return machine.isClientSide();}

    /**
     * Open typed/raw data channel — read or write custom data from conditions and actions without
     * modifying any framework type: {@code ctx.data().get(MY_KEY)} (typed) or
     * {@code ctx.data().get("name")} (raw). Backed by {@link StateMachine#data()} (persists across ticks).
     */
    public Blackboard data() {return machine.data();}

    /**
     * Imperatively switch to {@code target} within the current layer (resolved this tick).
     */
    public void goTo(State<Owner> target) {layer.requestGoTo(target);}

    /**
     * Fire a named trigger (consumed at end of this tick).
     */
    public void trigger(String name) {machine.trigger(name);}

    /**
     * Lock another layer for {@code ticks} ticks (it stops evaluating transitions, but still emits its pose).
     */
    public void lockLayer(String layerId, int ticks) {machine.lockLayer(layerId, ticks);}

    /**
     * Read a named signal (data-condition channel, reserved for JSON/scripts).
     */
    public Object signal(String name) {return machine.signal(name);}

    public void signal(String name, Object value) {machine.setSignal(name, value);}

    /**
     * Convenience for code that prefers a block: runs {@code body} with this context.
     */
    public void run(Consumer<StateContext<Owner>> body) {body.accept(this);}

    //region StateReader — read current and machine-level collaborative state

    @Override
    public boolean has(StateQuery query) {
        return read(query) != null;
    }

    @Override
    public Object read(StateQuery query) {
        if (query.isEmpty()) return null;
        String source = query.source();
        return switch (source) {
            case "owner", "machine", "data", "signal", "trigger" -> machine.read(query);
            case "layer" -> readLayer(query.segment(1));
            case "state" -> readState(query.segment(1));
            default -> null;
        };
    }

    private Object readLayer(String prop) {
        if (prop == null || layer == null) return null;
        State<Owner> active = layer.active();
        return switch (prop) {
            case "id" -> layer.id();
            case "state" -> active == null ? null : active.id();
            case "mode" -> layer.mode();
            case "weight" -> layer.weight();
            case "locked" -> machine.isLayerLocked(layer.id());
            case "elapsed" -> layer.stateElapsedTicks();
            case "duration" -> active == null ? -1 : active.durationTicks();
            case "transition" -> layer.activeTransition();
            case "transitionElapsed" -> layer.transitionElapsed();
            case "mask" -> layer.boneMask();
            default -> null;
        };
    }

    private Object readState(String prop) {
        if (prop == null || state == null) return null;
        return switch (prop) {
            case "id" -> state.id();
            case "elapsed" -> layer == null ? 0 : layer.stateElapsedTicks();
            case "duration" -> state.durationTicks();
            default -> null;
        };
    }

    //endregion
}
