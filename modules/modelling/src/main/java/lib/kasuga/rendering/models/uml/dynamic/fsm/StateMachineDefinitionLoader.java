package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import lib.kasuga.KasugaLib;
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
 * and registers them with {@link MachineRegistry#GLOBAL}. Reloaded automatically when the scoped
 * resource manager reloads.
 */
public final class StateMachineDefinitionLoader implements ScopedResourceManagerConsumer, ScopedResourcePackListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PATH = "state_machines";

    public static final StateMachineDefinitionLoader INSTANCE = new StateMachineDefinitionLoader();

    private StateMachineDefinitionLoader() {}

    @Override
    public void onResourceManagerAdded(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        resourceManager.addListener(this);
    }

    @Override
    public void onResourceManagerRemoved(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        // Definitions are cleared on reload; leave stale data during transitions.
    }

    @Override
    public void onReloaded(ScopedResourceManager resourceManager) {
        load(resourceManager.getResourceManager());
    }

    public void load(ResourceManager resourceManager) {
        MachineRegistry.GLOBAL.clear();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(PATH, loc -> loc.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation loc = entry.getKey();
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                com.google.gson.JsonElement json = com.google.gson.JsonParser.parseReader(reader);
                StateMachineDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LOGGER.error("Failed to decode state machine '{}': {}", loc, error))
                        .ifPresent(result -> {
                            StateMachineDefinition definition = result.getFirst();
                            ResourceLocation id = definition.id();
                            MachineRegistry.GLOBAL.registerDefinition(id, definition);
                            LOGGER.info("Loaded state machine definition '{}' from {}", id, loc);
                        });
            } catch (IOException e) {
                LOGGER.error("Failed to read state machine definition {}", loc, e);
            }
        }
    }

    public static void install() {
        ResourceSystem system = KasugaLib.getBean(ResourceSystem.class);
        if (system != null) {
            system.registerConsumer(INSTANCE);
        }
    }
}
