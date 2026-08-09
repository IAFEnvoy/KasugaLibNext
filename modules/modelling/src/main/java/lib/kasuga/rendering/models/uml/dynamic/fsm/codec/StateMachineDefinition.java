package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Data-driven definition of an animation state machine. Stores typed {@link StateVarDefinition state vars},
 * structure (layers, states, transitions, pose targets), and behavior references (guards / actions / pose
 * resolution) resolved at runtime through
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary}.
 */
public record StateMachineDefinition(
        ResourceLocation id,
        List<StateVarDefinition> stateVars,
        List<LayerDefinition> layers
) {

    public static final Codec<StateMachineDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(StateMachineDefinition::id),
            StateVarDefinition.CODEC.listOf().optionalFieldOf("state_vars", List.of()).forGetter(StateMachineDefinition::stateVars),
            LayerDefinition.CODEC.listOf().fieldOf("layers").forGetter(StateMachineDefinition::layers)
    ).apply(instance, StateMachineDefinition::new));
}
