package lib.kasuga.rendering.models.mc.registry;

import com.google.gson.JsonObject;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be.BEModelLoader;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je.JEModelLoader;
import lib.kasuga.rendering.models.mc.source.model.KasugaPipeLineRouter;
import lib.kasuga.rendering.models.mc.source.model.json.FileJsonModelSource;
import lib.kasuga.rendering.models.mc.source.model.json.JarJsonModelSource;
import lib.kasuga.rendering.models.mc.source.model.json.JsonModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.str.FileStrModelSource;
import lib.kasuga.rendering.models.mc.source.model.str.JarStrModelSource;
import lib.kasuga.rendering.models.mc.source.model.str.StrModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.zip.FileZipModelSource;
import lib.kasuga.rendering.models.mc.source.model.zip.JarZipModelSource;
import lib.kasuga.rendering.models.mc.source.model.zip.ZipModelSourceManager;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.typo.KsgObjLoader;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class PipelineRegistry {

    public static final String BE = "be";
    public static final String JE = "je";
    public static final String OBJ = "obj";
    public static final String PMX = "pmx";

    private static final Map<String, ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> PIPELINES =
            new ConcurrentHashMap<>();

    private static final Map<String, String> BUILTIN_ROUTES = new LinkedHashMap<>();
    static {
        BUILTIN_ROUTES.put(".geo.json", BE);
        BUILTIN_ROUTES.put(".obj", OBJ);
        BUILTIN_ROUTES.put(".mmd.zip", PMX);
        BUILTIN_ROUTES.put(".json", JE);
    }

    private static KasugaPipeLineRouter router;

    private static MCBackend backend;
    private static KsgPmxLoader pmxLoader;

    private static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> bePipeline;
    private static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> jePipeline;
    private static ModelPipeLine<String, BackendInstance, ResourceLocation, ResourceLocation, String> objPipeline;
    private static ModelPipeLine<ZipHelper, BackendInstance, ResourceLocation, ResourceLocation, ZipResource> pmxPipeline;

    private PipelineRegistry() {
    }

    public static void registerBuiltins(CombinedTextureManager textures) {
        JsonModelSourceManager jsonSource = new JsonModelSourceManager("json");
        StrModelSourceManager strSource = new StrModelSourceManager("str");
        ZipModelSourceManager zipSource = new ZipModelSourceManager("zip");

        jsonSource.registerSource(new FileJsonModelSource("file_json"));
        jsonSource.registerSource(new JarJsonModelSource("jar_json"));
        strSource.registerSource(new FileStrModelSource("file_str"));
        strSource.registerSource(new JarStrModelSource("jar_str"));
        zipSource.registerSource(new FileZipModelSource("file_zip"));
        zipSource.registerSource(new JarZipModelSource("jar_zip"));

        MCBridge bridge = new MCBridge();
        backend = new MCBackend();

        bePipeline = new ModelPipeLine.Builder<JsonObject, BackendInstance, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(jsonSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new BEModelLoader("be_model", KasugaLib.MODID))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(BE, bePipeline);

        jePipeline = new ModelPipeLine.Builder<JsonObject, BackendInstance, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(jsonSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new JEModelLoader("je_model"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(JE, jePipeline);

        objPipeline = new ModelPipeLine.Builder<String, BackendInstance, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(strSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(new KsgObjLoader("obj_model"))
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(OBJ, objPipeline);

        pmxLoader = new KsgPmxLoader("pmx_model");
        pmxPipeline = new ModelPipeLine.Builder<ZipHelper, BackendInstance, ResourceLocation,
                ResourceLocation, ZipResource>()
                .withModelSource(zipSource)
                .withSidedSource(textures.getType(), "mc_layer_0", textures)
                .withLoader(pmxLoader)
                .withBridge("mc_bridge", bridge)
                .withBackend("mc_backend", backend)
                .build();
        register(PMX, pmxPipeline);
    }

    public static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> be() {
        return bePipeline;
    }

    public static ModelPipeLine<JsonObject, BackendInstance, ResourceLocation, ResourceLocation, String> je() {
        return jePipeline;
    }

    public static ModelPipeLine<String, BackendInstance, ResourceLocation, ResourceLocation, String> obj() {
        return objPipeline;
    }

    public static ModelPipeLine<ZipHelper, BackendInstance, ResourceLocation, ResourceLocation, ZipResource> pmx() {
        return pmxPipeline;
    }

    public static MCBackend backend() {
        return backend;
    }

    public static KsgPmxLoader pmxLoader() {
        return pmxLoader;
    }

    public static void register(String id, ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pipeline, "pipeline");
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> previous = PIPELINES.putIfAbsent(id, pipeline);
        if (previous != null && previous != pipeline) {
            throw new IllegalStateException("Pipeline '" + id + "' is already registered to a different pipeline");
        }
    }

    @Nullable
    public static ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> get(String id) {
        if (id == null) {
            return null;
        }
        return PIPELINES.get(id);
    }

    public static boolean has(String id) {
        return id != null && PIPELINES.containsKey(id);
    }

    public static void registerDefaultRoutes(KasugaPipeLineRouter router) {
        PipelineRegistry.router = router;
        BUILTIN_ROUTES.forEach((extension, id) ->
                router.registerByExtension(extension, () -> get(id)));
    }

    public static void registerRoute(KasugaPipeLineRouter router, String extension, String id) {
        router.registerByExtension(extension, () -> get(id));
    }

    @Nullable
    public static ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> resolve(ResourceLocation modelKey) {
        if (router == null) {
            throw new IllegalStateException("KasugaPipeLineRouter is not initialized yet (registerDefaultRoutes)");
        }
        return router.resolve(modelKey);
    }
}
