package lib.kasuga.rendering.models.uml.dynamic.fsm.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven transition. Guards, fire actions and triggers are resolved at runtime
 * through {@link lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary}.
 */
public record TransitionDefinition(
        String id,
        String from,
        String to,
        Optional<String> triggerOn,
        boolean whenComplete,
        float crossFadeSeconds,
        List<ResourceLocation> when,
        List<ResourceLocation> onFire
) {

    public static final Codec<TransitionDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(TransitionDefinition::id),
            Codec.STRING.fieldOf("from").forGetter(TransitionDefinition::from),
            Codec.STRING.fieldOf("to").forGetter(TransitionDefinition::to),
            Codec.STRING.optionalFieldOf("trigger_on").forGetter(TransitionDefinition::triggerOn),
            Codec.BOOL.optionalFieldOf("when_complete", false).forGetter(TransitionDefinition::whenComplete),
            Codec.FLOAT.optionalFieldOf("cross_fade_seconds", 0f).forGetter(TransitionDefinition::crossFadeSeconds),
            ResourceLocation.CODEC.listOf().optionalFieldOf("when", List.of()).forGetter(TransitionDefinition::when),
            ResourceLocation.CODEC.listOf().optionalFieldOf("on_fire", List.of()).forGetter(TransitionDefinition::onFire)
    ).apply(instance, TransitionDefinition::new));
}
