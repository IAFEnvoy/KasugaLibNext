package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Looks up {@link StateMachine}s by {@link ResourceLocation} (and by long handle for the
 * scripting API). Replaces the old DefinitionRegistry — here the machine IS the definition.
 */
public final class MachineRegistry {

    /**
     * Process-wide shared registry backing the scripting API: every engine's "Animator" global resolves
     * handles against this single instance. Replaces the old {@code FsmNetworking.machines} holder.
     */
    public static final MachineRegistry GLOBAL = new MachineRegistry();

    private final Map<ResourceLocation, StateMachineDefinition> definitionsById = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, StateMachine<?>> byId = new ConcurrentHashMap<>();
    private final Map<Long, StateMachine<?>> byHandle = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1);

    /** Register a data-driven definition (JSON). */
    public void registerDefinition(ResourceLocation id, StateMachineDefinition definition) {
        definitionsById.put(id, definition);
    }

    public StateMachineDefinition getDefinition(ResourceLocation id) {
        return definitionsById.get(id);
    }

    /** Register a runtime machine instance and obtain a scripting handle. */
    public long register(ResourceLocation id, StateMachine<?> machine) {
        byId.put(id, machine);
        long handle = nextHandle.getAndIncrement();
        byHandle.put(handle, machine);
        return handle;
    }

    public StateMachine<?> get(ResourceLocation id) {
        return byId.get(id);
    }

    public StateMachine<?> resolve(long handle) {
        return byHandle.get(handle);
    }

    public void invalidate(long handle) {
        byHandle.remove(handle);
    }

    public void clear() {
        definitionsById.clear();
        byId.clear();
        byHandle.clear();
    }
}
