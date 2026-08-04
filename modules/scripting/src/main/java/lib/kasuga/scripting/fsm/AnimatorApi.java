package lib.kasuga.scripting.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.MachineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.scripting.security.Api;

/**
 * Script-facing control surface for {@link StateMachine}s — a polyglot-neutral API keyed by
 * a long handle (from {@link MachineRegistry#register}). Registered as the engine global "Animator"
 * via {@link FsmApiRegistration#install}. Scripts: {@code Animator.trigger(handle, "attack")},
 * {@code Animator.goTo(handle, "upper_body", "attack.windup")},
 * {@code Animator.read(handle, "layer.upper_body.state")}.
 */
public final class AnimatorApi {

    private final MachineRegistry registry;

    public AnimatorApi() {
        this(MachineRegistry.GLOBAL);
    }

    public AnimatorApi(MachineRegistry registry) {
        this.registry = registry;
    }

    private StateMachine<?> machine(long handle) {
        return registry.resolve(handle);
    }

    @Api
    public void trigger(long handle, String name) {
        StateMachine<?> m = machine(handle);
        if (m != null) m.trigger(name);
    }

    @Api
    public void goTo(long handle, String layerId, String stateId) {
        StateMachine<?> m = machine(handle);
        if (m != null) m.goTo(layerId, stateId);
    }

    @Api
    public String getState(long handle, String layerId) {
        StateMachine<?> m = machine(handle);
        return m == null ? "" : m.readString("layer." + layerId + ".state");
    }

    @Api
    public void signal(long handle, String name, Object value) {
        StateMachine<?> m = machine(handle);
        if (m != null) m.setSignal(name, value);
    }

    @Api
    public Object signal(long handle, String name) {
        StateMachine<?> m = machine(handle);
        return m == null ? null : m.read("signal." + name);
    }

    @Api
    public Object read(long handle, String path) {
        StateMachine<?> m = machine(handle);
        return m == null ? null : m.read(path);
    }

    @Api
    public boolean readBool(long handle, String path) {
        StateMachine<?> m = machine(handle);
        return m != null && m.readBool(path);
    }

    @Api
    public int readInt(long handle, String path) {
        StateMachine<?> m = machine(handle);
        return m == null ? 0 : m.readInt(path);
    }

    @Api
    public float readFloat(long handle, String path) {
        StateMachine<?> m = machine(handle);
        return m == null ? 0f : m.readFloat(path);
    }

    @Api
    public String readString(long handle, String path) {
        StateMachine<?> m = machine(handle);
        return m == null ? "" : m.readString(path);
    }
}
