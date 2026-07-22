package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.mc.multiplexer.McContext;
import lib.kasuga.rendering.models.mc.multiplexer.McVariant;
import lib.kasuga.rendering.models.uml.dynamic.data.Blackboard;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Multiplexer;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.MuxState;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.SelectorPredicates;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the open {@link Blackboard} extension channel: typed/raw keys on {@link StateContext},
 * and custom channels on {@link McContext} — no framework type edited to add new data.
 */
class ExtensibilityTest {

    static final Blackboard.Key<Float> SPEED = Blackboard.Key.of("speed");
    static final Blackboard.Key<String> MODE = Blackboard.Key.of("mode");
    static final Blackboard.Key<Boolean> ARMED = Blackboard.Key.of("armed");

    static final class Actor {}

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_lib", path);
    }

    @Test
    void contextBlackboardTypedAndRaw() {
        Actor owner = new Actor();
        StateMachine<Actor> machine = StateMachine.<Actor>builder(owner)
                .layer("l", layer -> {
                    State<Actor> a = layer.state("a");
                    State<Actor> b = layer.state("b");
                    layer.initial(a);
                    layer.transition("a2b", a, b)
                            .when(ctx -> ctx.data().getOrDefault(SPEED, 0f) > 0.5f)
                            .onFire(ctx -> ctx.data().put(MODE, "fast"));
                })
                .build();

        machine.data().put(SPEED, 0.6f);
        machine.tick();

        assertEquals("b", machine.layer("l").active().id());
        assertEquals("fast", machine.data().get(MODE));

        machine.data().put("note", "hello");
        assertEquals("hello", machine.data().get("note"));
    }

    @Test
    void multiplexerCustomChannel() {
        Multiplexer<McContext, McVariant> def = Multiplexer.define(McVariant::new, mux -> {
            McVariant off = mux.variant("off", v -> v.model(rl("off")));
            McVariant on = mux.variant("on", v -> v.model(rl("on")));
            mux.transition(off, on, t -> t.when(SelectorPredicates.dataFlag(ARMED)));
            mux.transition(on, off, t -> t.when(in -> !Boolean.TRUE.equals(in.data().get(ARMED))));
            mux.initial(off);
        });

        MuxState<McVariant> state = def.newState();
        McContext input = new McContext(
                Map.of("powered", "false"), List.of(), 0, 0L, Set.of());

        input.data().put(ARMED, true);
        def.advance(state, input, 0f);
        assertSame(def.variant("on"), state.current());

        input.data().put(ARMED, false);
        def.advance(state, input, 0f);
        assertSame(def.variant("off"), state.current());
    }
}
