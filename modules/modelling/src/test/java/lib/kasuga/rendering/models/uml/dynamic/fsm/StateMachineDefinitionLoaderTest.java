package lib.kasuga.rendering.models.uml.dynamic.fsm;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StateMachineDefinitionLoader#load} against a stub {@link ResourceManager}: definitions
 * land in the injected bucket's RESOURCE section, broken JSON does not interrupt the batch, a
 * second load replaces RESOURCE definitions while SCRIPT definitions survive, and the per-id
 * content hash tracks definition identity.
 */
class StateMachineDefinitionLoaderTest {

    /** Minimal in-memory resource manager serving the given virtual files. */
    private static final class StubResourceManager implements ResourceManager {
        private final Map<ResourceLocation, String> files = new HashMap<>();

        StubResourceManager add(ResourceLocation loc, String content) {
            files.put(loc, content);
            return this;
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, java.util.function.Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new HashMap<>();
            files.forEach((loc, content) -> {
                if (loc.getPath().startsWith(path) && filter.test(loc)) {
                    result.put(loc, new Resource(null,
                            () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
                }
            });
            return result;
        }

        @Override
        public Map<ResourceLocation, java.util.List<Resource>> listResourceStacks(String path,
                                                                                  java.util.function.Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, java.util.List<Resource>> result = new HashMap<>();
            listResources(path, filter).forEach((loc, resource) -> result.put(loc, java.util.List.of(resource)));
            return result;
        }

        @Override
        public java.util.Set<String> getNamespaces() {
            return files.keySet().stream().map(ResourceLocation::getNamespace).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public java.util.List<Resource> getResourceStack(ResourceLocation location) {
            return getResource(location).map(java.util.List::of).orElseGet(java.util.List::of);
        }

        @Override
        public java.util.stream.Stream<net.minecraft.server.packs.PackResources> listPacks() {
            return java.util.stream.Stream.of();
        }

        @Override
        public java.util.Optional<Resource> getResource(ResourceLocation location) {
            String content = files.get(location);
            if (content == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Resource(null,
                    () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private static final String GOOD_JSON = """
            {
              "id": "test:good",
              "layers": [
                { "id": "l", "mode": "base", "weight": 1.0, "bone_mask": "*",
                  "initial_state": "idle",
                  "states": [ { "id": "idle", "duration_ticks": 10 } ] }
              ]
            }
            """;

    private static final String BROKEN_JSON = "{ this is not json }";

    @Test
    void loadsValidDefinitionsIntoInjectedRegistry() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);

        loader.load(manager);

        assertNotNull(definitions.get(ResourceLocation.fromNamespaceAndPath("test", "good")));
    }

    @Test
    void brokenJsonDoesNotAbortTheBatch() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/broken.json"), BROKEN_JSON)
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);

        loader.load(manager);

        assertNull(definitions.get(ResourceLocation.fromNamespaceAndPath("test", "broken")));
        assertNotNull(definitions.get(ResourceLocation.fromNamespaceAndPath("test", "good")));
    }

    @Test
    void reloadReplacesResourceDefinitionsAndKeepsScriptDefinitions() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);

        StubResourceManager first = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);
        loader.load(first);
        assertNotNull(definitions.get(ResourceLocation.fromNamespaceAndPath("test", "good")));

        // a script definition on the same registry must survive reloads
        ResourceLocation scriptId = ResourceLocation.fromNamespaceAndPath("test", "script_def");
        definitions.register(scriptId, definitions.get(ResourceLocation.fromNamespaceAndPath("test", "good")));

        // second load with an empty pack: RESOURCE definitions go away, SCRIPT stays
        loader.load(new StubResourceManager());
        assertNull(definitions.get(ResourceLocation.fromNamespaceAndPath("test", "good")));
        assertNotNull(definitions.get(scriptId));
    }

    @Test
    void hashTracksDefinitionIdentityAcrossReloadAndOverwrite() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        ResourceLocation good = ResourceLocation.fromNamespaceAndPath("test", "good");
        assertEquals(0, definitions.hash(good), "absent id hashes to 0");

        loader.load(new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON));
        int loaded = definitions.hash(good);
        assertNotEquals(0, loaded, "a loaded definition has a non-zero content hash");

        // reloading the same content keeps the hash; overwriting with different content changes it
        loader.load(new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON));
        assertEquals(loaded, definitions.hash(good), "same content -> same hash");

        definitions.register(good, new lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition(
                good, java.util.List.of(), java.util.List.of()));
        assertNotEquals(loaded, definitions.hash(good), "different content -> different hash");
    }

    @Test
    void sameJsonCanBeLoadedTwiceWithoutError() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);
        loader.load(manager);
        loader.load(manager);
        assertNotNull(definitions.get(ResourceLocation.fromNamespaceAndPath("test", "good")));
    }
}
