package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;

/**
 * Guard predicate resolved at runtime from a data-driven {@link net.minecraft.resources.ResourceLocation}.
 */
@FunctionalInterface
public interface FsmCondition {

    boolean test(StateContext<?> ctx);
}
