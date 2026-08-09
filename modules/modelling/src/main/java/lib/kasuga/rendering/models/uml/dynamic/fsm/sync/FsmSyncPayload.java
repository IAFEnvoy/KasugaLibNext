package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

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
 * @param machineId           definition id of the machine
 * @param dimension           dimension the machine lives in
 * @param ownerDiscriminator  host discriminator (block entity = worldPosition.asLong())
 * @param version             server machine version at push time
 * @param definitionHash      content hash of the definition (per-id identity check)
 * @param force               true on the periodic heartbeat: bypass client shouldApply
 * @param layers              per-layer state, in layer build order
 */
public record FsmSyncPayload(
        ResourceLocation machineId,
        ResourceLocation dimension,
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
        return new Head(machineId, dimension, ownerDiscriminator, version, definitionHash, force);
    }

    public static final StreamCodec<FriendlyByteBuf, FsmSyncPayload> CODEC = StreamCodec.composite(
            Head.CODEC, FsmSyncPayload::head,
            LayerEntry.CODEC.apply(ByteBufCodecs.list()), FsmSyncPayload::layers,
            (head, layers) -> new FsmSyncPayload(
                    head.machineId(), head.dimension(), head.ownerDiscriminator(),
                    head.version(), head.definitionHash(), head.force(), layers)
    );
}
