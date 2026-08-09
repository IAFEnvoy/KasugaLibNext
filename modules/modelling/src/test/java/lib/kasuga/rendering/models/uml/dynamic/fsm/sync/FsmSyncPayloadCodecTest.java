package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import io.netty.buffer.Unpooled;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FsmSyncPayload wire round-trip + FsmSyncClient.apply: index→id conversion and validation. */
class FsmSyncPayloadCodecTest {

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static StateMachine<Object> machine() {
        return StateMachine.builder(new Object())
                .layer("loco", layer -> {
                    State<Object> idle = layer.state("idle").durationTicks(2);
                    State<Object> walk = layer.state("walk");
                    layer.initial(idle);
                    layer.transition("idle_to_walk", idle, walk).whenComplete().crossFade(0.25f);
                })
                .build();
    }

    private static FsmSyncPayload samplePayload() {
        return new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 7, 9, false,
                List.of(new FsmSyncPayload.LayerEntry(0, 1, 3, -1, 0f))
        );
    }

    @Test
    void roundTripsThroughFriendlyByteBuf() {
        FsmSyncPayload payload = samplePayload();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        FsmSyncPayload.CODEC.encode(buf, payload);
        FsmSyncPayload decoded = FsmSyncPayload.CODEC.decode(buf);
        assertEquals(payload, decoded);
    }

    @Test
    void emptyLayersRoundTrip() {
        FsmSyncPayload payload = new FsmSyncPayload(rl("demo"), rl("overworld"), 0L, 1, 2, true, List.of());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        FsmSyncPayload.CODEC.encode(buf, payload);
        assertEquals(payload, FsmSyncPayload.CODEC.decode(buf));
    }

    @Test
    void applyConformsMachineFromIndices() {
        StateMachine<Object> server = machine();
        server.tick();
        server.tick();
        server.tick(); // idle completes → crossfade idle→walk in flight

        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        FsmSyncKey key = new FsmSyncKey(rl("demo"), rl("overworld"), 123L);
        clientSync.bind(key, client);

        // hand-built server payload: indices taken from the server machine's own structure
        FsmSyncPayload payload = new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, server.version(), 9, false,
                List.of(new FsmSyncPayload.LayerEntry(
                        0,
                        server.layer("loco").activeStateIndex(),
                        server.layer("loco").stateElapsedTicks(),
                        server.layer("loco").activeTransitionIndex(),
                        server.layer("loco").transitionElapsed()
                ))
        );

        clientSync.apply(payload);

        assertEquals(server.layer("loco").active().id(), client.layer("loco").active().id());
        assertEquals(server.layer("loco").stateElapsedTicks(), client.layer("loco").stateElapsedTicks());
        assertEquals(server.layer("loco").activeTransition().id(), client.layer("loco").activeTransition().id());
        assertEquals(server.layer("loco").transitionElapsed(), client.layer("loco").transitionElapsed(), 1e-4f);
    }

    @Test
    void staleVersionsAreIgnoredAfterApply() {
        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        FsmSyncKey key = new FsmSyncKey(rl("demo"), rl("overworld"), 123L);
        clientSync.bind(key, client);

        clientSync.apply(new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 3, 9, false,
                List.of(new FsmSyncPayload.LayerEntry(0, 1, 0, -1, 0f))
        ));
        assertEquals(1, client.version()); // conform applied

        clientSync.apply(new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 3, 9, false,
                List.of(new FsmSyncPayload.LayerEntry(0, 0, 0, -1, 0f))
        ));
        assertEquals(1, client.version()); // stale: dropped before conform
    }

    @Test
    void outOfBoundsIndicesAreSkippedWithoutThrowing() {
        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        FsmSyncKey key = new FsmSyncKey(rl("demo"), rl("overworld"), 123L);
        clientSync.bind(key, client);

        FsmSyncPayload payload = new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 1, 9, false,
                List.of(
                        new FsmSyncPayload.LayerEntry(9, 0, 0, -1, 0f), // layer index OOB
                        new FsmSyncPayload.LayerEntry(0, 9, 0, -1, 0f), // state index OOB
                        new FsmSyncPayload.LayerEntry(0, 9, 0, 9, 0f)   // state OOB → skipped before transition
                )
        );
        clientSync.apply(payload); // must not throw

        assertEquals(0, client.version()); // all entries OOB → nothing conformed
        assertEquals("idle", client.layer("loco").active().id());
    }

    @Test
    void definitionHashMismatchIsRejected() {
        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        FsmSyncKey key = new FsmSyncKey(rl("demo"), rl("overworld"), 123L);
        clientSync.bind(key, client);

        FsmSyncPayload payload = new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 1, 8, false, // server def hash 8 ≠ local 9
                List.of(new FsmSyncPayload.LayerEntry(0, 1, 0, -1, 0f))
        );
        clientSync.apply(payload);

        assertEquals("idle", client.layer("loco").active().id()); // unchanged
        assertEquals(0, client.version());
    }

    @Test
    void forceHeartbeatBypassesStalenessAndApplies() {
        StateMachine<Object> client = machine();
        FsmSyncClient clientSync = new FsmSyncClient(new FsmSyncState(), id -> 9);
        FsmSyncKey key = new FsmSyncKey(rl("demo"), rl("overworld"), 123L);
        clientSync.bind(key, client);

        clientSync.apply(new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 5, 9, false,
                List.of(new FsmSyncPayload.LayerEntry(0, 0, 0, -1, 0f))
        ));
        assertTrue(client.version() > 0);

        // a forced replay of an older version must still apply
        clientSync.apply(new FsmSyncPayload(
                rl("demo"), rl("overworld"), 123L, 2, 9, true,
                List.of(new FsmSyncPayload.LayerEntry(0, 1, 5, -1, 0f))
        ));
        assertEquals("walk", client.layer("loco").active().id());
    }
}
