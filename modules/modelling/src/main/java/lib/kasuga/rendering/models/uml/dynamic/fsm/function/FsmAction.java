package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;

/**
 * Action callback resolved at runtime from a data-driven {@link net.minecraft.resources.ResourceLocation}.
 * Generic over the owner type so a Java-side action receives a typed {@code StateContext<MyActor>} (no cast).
 */
@FunctionalInterface
public interface FsmAction<O> {

    void accept(StateContext<O> ctx);
}
