package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven state node. Enter/exit/update actions and the pose target are resolved
 * by {@link ResourceLocation} references.
 */
public record StateDefinition(
        String id,
        Optional<Integer> durationTicks,
        PoseDefinition pose,
        List<ResourceLocation> onEnter,
        List<ResourceLocation> onExit,
        List<ResourceLocation> onUpdate
) {

    public static final Codec<StateDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(StateDefinition::id),
            Codec.INT.optionalFieldOf("duration_ticks").forGetter(StateDefinition::durationTicks),
            PoseDefinition.CODEC.optionalFieldOf("pose", PoseDefinition.EMPTY).forGetter(StateDefinition::pose),
            ResourceLocation.CODEC.listOf().optionalFieldOf("on_enter", List.of()).forGetter(StateDefinition::onEnter),
            ResourceLocation.CODEC.listOf().optionalFieldOf("on_exit", List.of()).forGetter(StateDefinition::onExit),
            ResourceLocation.CODEC.listOf().optionalFieldOf("on_update", List.of()).forGetter(StateDefinition::onUpdate)
    ).apply(instance, StateDefinition::new));
}
