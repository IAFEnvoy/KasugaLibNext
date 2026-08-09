package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry for data-driven FSM behavior. Java code and scripting engines register
 * guards ({@link FsmCondition}) and actions ({@link FsmAction}) by {@link ResourceLocation}, which JSON
 * definitions reference. The shared instance lives on the composition root
 * ({@link lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries}).
 */
public final class FsmFunctionLibrary {

    private final Map<ResourceLocation, FsmCondition<?>> conditions = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, FsmAction<?>> actions = new ConcurrentHashMap<>();

    public <O> void registerCondition(ResourceLocation id, FsmCondition<O> condition) {
        conditions.put(id, condition);
    }

    public <O> void registerAction(ResourceLocation id, FsmAction<O> action) {
        actions.put(id, action);
    }

    public FsmCondition<?> condition(ResourceLocation id) {
        return conditions.get(id);
    }

    public FsmAction<?> action(ResourceLocation id) {
        return actions.get(id);
    }

    public boolean hasCondition(ResourceLocation id) {
        return conditions.containsKey(id);
    }

    public boolean hasAction(ResourceLocation id) {
        return actions.containsKey(id);
    }

    /**
     * Drop every condition and action registered under this namespace. Leaves other namespaces
     * untouched — reserved for future script-package reloads; the resource loader does NOT clear
     * the library on reload (lifecycle decoupled).
     */
    public void clearNamespace(String namespace) {
        if (namespace == null) {
            return;
        }
        conditions.keySet().removeIf(id -> namespace.equals(id.getNamespace()));
        actions.keySet().removeIf(id -> namespace.equals(id.getNamespace()));
    }

    public void clear() {
        conditions.clear();
        actions.clear();
    }
}
