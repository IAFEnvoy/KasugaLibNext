package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry for data-driven FSM behavior. Java code and scripting engines register
 * guards ({@link FsmCondition}) and actions ({@link FsmAction}) by {@link ResourceLocation},
 * which JSON definitions reference.
 */
public final class FsmFunctionLibrary {

    public static final FsmFunctionLibrary GLOBAL = new FsmFunctionLibrary();

    private final Map<ResourceLocation, FsmCondition> conditions = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, FsmAction> actions = new ConcurrentHashMap<>();

    public void registerCondition(ResourceLocation id, FsmCondition condition) {
        conditions.put(id, condition);
    }

    public void registerAction(ResourceLocation id, FsmAction action) {
        actions.put(id, action);
    }

    public FsmCondition condition(ResourceLocation id) {
        return conditions.get(id);
    }

    public FsmAction action(ResourceLocation id) {
        return actions.get(id);
    }

    public boolean hasCondition(ResourceLocation id) {
        return conditions.containsKey(id);
    }

    public boolean hasAction(ResourceLocation id) {
        return actions.containsKey(id);
    }

    public void clear() {
        conditions.clear();
        actions.clear();
    }
}
