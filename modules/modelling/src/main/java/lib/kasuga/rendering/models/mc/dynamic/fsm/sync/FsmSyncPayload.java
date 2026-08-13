package lib.kasuga.rendering.models.mc.dynamic.fsm.sync;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server → client FSM state payload; layer/state/transition indices resolve against the shared
 * definition. {@code definitionHash} guards against mismatched definitions; out-of-bounds
 * indices are skipped with a warning instead of failing.
 *
 * <p>The public fields use the pure {@link Id} (machineId) + a dimension {@code String} (matching
 * {@link FsmSyncKey}); the {@link Head} wire record keeps {@link ResourceLocation} for both so the
 * {@code ResourceLocation.STREAM_CODEC} wire format is byte-identical (no protocol bump) — the Id↔RL
 * conversion happens in {@link #head()} / the decode lambda.
 *
 * @param machineId           definition id of the machine
 * @param dimension           dimension the machine lives in ({@code level.dimension().location().toString()})
 * @param ownerDiscriminator  host discriminator (block entity = worldPosition.asLong())
 * @param version             server machine version at push time
 * @param definitionHash      content hash of the definition (per-id identity check)
 * @param force               true on the periodic heartbeat: bypass client shouldApply
 * @param layers              per-layer state, in layer build order
 */
public record FsmSyncPayload(
        Id machineId,
        String dimension,
        long ownerDiscriminator,
        int version,
        int definitionHash,
        boolean force,
        List<LayerEntry> layers
) implements CustomPacketPayload {

    /** One layer's runtime state, indexed into the shared definition. */
    public record LayerEntry(
            int layerIndex,
            int stateIndex /* -1 = none */,
            int elapsedTicks,
            int transitionIndex /* -1 = none */,
            float transitionElapsedSeconds
    ) {

        public static final StreamCodec<FriendlyByteBuf, LayerEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LayerEntry::layerIndex,
                ByteBufCodecs.VAR_INT, LayerEntry::stateIndex,
                ByteBufCodecs.VAR_INT, LayerEntry::elapsedTicks,
                ByteBufCodecs.VAR_INT, LayerEntry::transitionIndex,
                ByteBufCodecs.FLOAT, LayerEntry::transitionElapsedSeconds,
                LayerEntry::new
        );
    }

    /**
     * Type follows the existing registration pattern (cf. {@code WithOutResponse.Wrapper.type()}):
     * resolve the registered entry directly on every call — no Lazy caching, the entry is set once
     * during payload registration.
     */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return FsmSyncChannel.PAYLOAD.getEntry();
    }

    /** Wire head — every scalar field; {@code composite} supports at most six fields, so layers sit outside. */
    record Head(
            ResourceLocation machineId,
            ResourceLocation dimension,
            long ownerDiscriminator,
            int version,
            int definitionHash,
            boolean force
    ) {

        private static final StreamCodec<FriendlyByteBuf, Head> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Head::machineId,
                ResourceLocation.STREAM_CODEC, Head::dimension,
                ByteBufCodecs.VAR_LONG, Head::ownerDiscriminator,
                ByteBufCodecs.VAR_INT, Head::version,
                ByteBufCodecs.VAR_INT, Head::definitionHash,
                ByteBufCodecs.BOOL, Head::force,
                Head::new
        );
    }

    private Head head() {
        return new Head(toRl(machineId), ResourceLocation.parse(dimension), ownerDiscriminator, version, definitionHash, force);
    }

    public static final StreamCodec<FriendlyByteBuf, FsmSyncPayload> CODEC = StreamCodec.composite(
            Head.CODEC, FsmSyncPayload::head,
            LayerEntry.CODEC.apply(ByteBufCodecs.list()), FsmSyncPayload::layers,
            (head, layers) -> new FsmSyncPayload(
                    toId(head.machineId()), head.dimension().toString(), head.ownerDiscriminator(),
                    head.version(), head.definitionHash(), head.force(), layers)
    );

    private static ResourceLocation toRl(Id id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath());
    }

    private static Id toId(ResourceLocation rl) {
        return Id.fromNamespaceAndPath(rl.getNamespace(), rl.getPath());
    }
}
