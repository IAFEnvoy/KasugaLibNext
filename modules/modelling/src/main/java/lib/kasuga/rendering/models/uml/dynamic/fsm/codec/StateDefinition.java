package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven state node. Enter/exit/update actions and the pose target are resolved
 * by {@link Id} references.
 */
public record StateDefinition(
        String id,
        Optional<Integer> durationTicks,
        PoseDefinition pose,
        List<Id> onEnter,
        List<Id> onExit,
        List<Id> onUpdate
) {

    public static final Codec<StateDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(StateDefinition::id),
            Codec.INT.optionalFieldOf("duration_ticks").forGetter(StateDefinition::durationTicks),
            PoseDefinition.CODEC.optionalFieldOf("pose", PoseDefinition.EMPTY).forGetter(StateDefinition::pose),
            Id.CODEC.listOf().optionalFieldOf("on_enter", List.of()).forGetter(StateDefinition::onEnter),
            Id.CODEC.listOf().optionalFieldOf("on_exit", List.of()).forGetter(StateDefinition::onExit),
            Id.CODEC.listOf().optionalFieldOf("on_update", List.of()).forGetter(StateDefinition::onUpdate)
    ).apply(instance, StateDefinition::new));
}
