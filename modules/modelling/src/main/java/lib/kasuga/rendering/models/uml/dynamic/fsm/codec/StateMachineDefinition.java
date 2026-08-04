package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Data-driven definition of an animation state machine. Stores only structure:
 * layers, states, transitions and pose targets. All behavior (guards, actions, pose resolution)
 * is referenced by {@link ResourceLocation} and resolved at runtime through
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary}.
 */
public record StateMachineDefinition(
        ResourceLocation id,
        List<LayerDefinition> layers
) {

    public static final Codec<StateMachineDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(StateMachineDefinition::id),
            LayerDefinition.CODEC.listOf().fieldOf("layers").forGetter(StateMachineDefinition::layers)
    ).apply(instance, StateMachineDefinition::new));
}
