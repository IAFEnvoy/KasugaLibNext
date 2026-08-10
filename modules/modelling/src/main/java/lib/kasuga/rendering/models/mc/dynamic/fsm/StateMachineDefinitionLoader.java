package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ScopedResourceManagerConsumer;
import lib.kasuga.core.resource.ScopedResourcePackListener;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

/**
 * Loads data-driven state machine definitions from {@code state_machines/*.json} in resource packs
 * and registers them with the injected {@link FsmDefinitions} (defaults to the shared bucket on
 * {@link FsmRegistries#GLOBAL}). Reloaded automatically when the scoped resource manager reloads.
 *
 * <p>Reload semantics: {@link #load(ResourceManager)} first clears the RESOURCE bucket
 * ({@link FsmDefinitions#clearResource()}) and re-populates it — already-built RESOURCE
 * machines keep running on the structure they were built from (definition bucket and instances are
 * decoupled by design); server-side machines only pick up new definitions when their block entity
 * is reloaded.
 */
@Context
public final class StateMachineDefinitionLoader implements ScopedResourceManagerConsumer, ScopedResourcePackListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PATH = "state_machines";

    private final FsmDefinitions definitions;

    @Inject
    ResourceSystem resourceSystem;

    public StateMachineDefinitionLoader() {
        this(FsmRegistries.GLOBAL.definitions());
    }

    /** Testable / host-injectable entry: pass a dedicated definition bucket. */
    public StateMachineDefinitionLoader(FsmDefinitions definitions) {
        this.definitions = definitions != null ? definitions : FsmRegistries.GLOBAL.definitions();
    }

    @PostConstruct
    public void init() {
        resourceSystem.registerConsumer(this);
    }

    @Override
    public void onResourceManagerAdded(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        resourceManager.addListener(this);
    }

    @Override
    public void onResourceManagerRemoved(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        // Machine instances are host-owned (never cleared here); the next reload re-populates definitions.
    }

    @Override
    public void onReloaded(ScopedResourceManager resourceManager) {
        load(resourceManager.getResourceManager());
    }

    public void load(ResourceManager resourceManager) {
        definitions.clearResource();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(PATH, loc -> loc.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation loc = entry.getKey();
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                com.google.gson.JsonElement json = com.google.gson.JsonParser.parseReader(reader);
                StateMachineDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LOGGER.error("Failed to decode state machine '{}': {}", loc, error))
                        .ifPresent(result -> {
                            StateMachineDefinition definition = result.getFirst();
                            Id id = definition.id();
                            definitions.registerResource(id, definition);
                            LOGGER.info("Loaded state machine definition '{}' from {}", id, loc);
                        });
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Failed to read state machine definition {}", loc, e);
            }
        }
    }
}
