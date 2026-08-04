package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;

/**
 * Action callback resolved at runtime from a data-driven {@link net.minecraft.resources.ResourceLocation}.
 */
@FunctionalInterface
public interface FsmAction {

    void accept(StateContext<?> ctx);
}
