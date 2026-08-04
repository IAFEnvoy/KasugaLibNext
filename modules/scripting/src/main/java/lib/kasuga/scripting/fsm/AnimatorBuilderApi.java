package lib.kasuga.scripting.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.MachineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.scripting.security.Api;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Script-facing builder for the data-driven animation state machine. Scripts register
 * guard predicates and action callbacks by {@link ResourceLocation}, then reference those
 * locations in JSON definitions loaded by {@link lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachineDefinitionLoader}.
 */
public final class AnimatorBuilderApi {

    private final FsmFunctionLibrary library;

    public AnimatorBuilderApi() {
        this(FsmFunctionLibrary.GLOBAL);
    }

    public AnimatorBuilderApi(FsmFunctionLibrary library) {
        this.library = library;
    }

    @Api
    public void registerCondition(String namespace, String path, Predicate<StateContext<?>> predicate) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        library.registerCondition(id, ctx -> predicate.test(ctx));
    }

    @Api
    public void registerAction(String namespace, String path, Consumer<StateContext<?>> action) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        library.registerAction(id, ctx -> action.accept(ctx));
    }

    @Api
    public String registerDefinition(String json) {
        JsonElement element = JsonParser.parseString(json);
        return StateMachineDefinition.CODEC.decode(JsonOps.INSTANCE, element)
                .resultOrPartial(error -> { throw new IllegalArgumentException("Invalid state machine JSON: " + error); })
                .map(result -> {
                    StateMachineDefinition definition = result.getFirst();
                    MachineRegistry.GLOBAL.registerDefinition(definition.id(), definition);
                    return definition.id().toString();
                })
                .orElse("");
    }

    @Api
    public String status() {
        return "AnimatorBuilderApi ready; register conditions/actions and definitions here.";
    }
}
