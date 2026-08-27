package lib.kasuga.rendering.models.mc;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.mixins.client.AccessorOnRegisterRenderTypesEvent;
import lib.kasuga.rendering.models.mc.backend.*;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be.BEModelLoader;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je.JEModelLoader;
import lib.kasuga.rendering.models.mc.source.model.zip.FileZipModelSource;
import lib.kasuga.rendering.models.mc.source.model.zip.JarZipModelSource;
import lib.kasuga.rendering.models.mc.source.model.zip.ZipModelSourceManager;
import lib.kasuga.rendering.models.mc.source.texture.BufferedImageTextureSource;
import lib.kasuga.rendering.models.mc.typo.KsgObjLoader;
import lib.kasuga.rendering.models.mc.typo.bbmodel.KsgBbModelLoader;
import lib.kasuga.rendering.models.mc.source.model.*;
import lib.kasuga.rendering.models.mc.source.model.json.FileJsonModelSource;
import lib.kasuga.rendering.models.mc.source.model.json.JarJsonModelSource;
import lib.kasuga.rendering.models.mc.source.model.json.JsonModelSourceManager;
import lib.kasuga.rendering.models.mc.source.model.str.FileStrModelSource;
import lib.kasuga.rendering.models.mc.source.model.str.JarStrModelSource;
import lib.kasuga.rendering.models.mc.source.model.str.StrModelSourceManager;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.source.texture.FileTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.JarTextureSource;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.event.*;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.List;

import static lib.kasuga.rendering.models.mc.backend.RenderState.UML_VERTEX_FORMAT;

@EventBusSubscriber(value = Dist.CLIENT)
public class Constants {

    public static ModelPipeLine BE_PIPELINE, OBJ_PIPELINE, MMD_PIPELINE, JE_PIPELINE, BBMODEL_PIPELINE;
    public static CombinedTextureManager TEXTURE_BASIC;
    public static SourceType TEXTURE_TYPE, MODEL_TYPE;
    public static MCBackend MC_BACKEND;
    public static KsgPmxLoader PMX_LOADER;
    public static UIBackend UI_BACKEND;

    public static ModelInstance currentInstance;

    @SubscribeEvent
    public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        if (UI_BACKEND == null) {
            UI_BACKEND = new UIBackend();
        }
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void onReloadListenerRegister(RegisterClientReloadListenersEvent event) {
        IrisCompat.onStart();
        TEXTURE_TYPE = new SourceType("texture");
        MODEL_TYPE = new SourceType("model");

        CombinedTextureManager basic = new CombinedTextureManager(
                TEXTURE_TYPE, "mc_layer_0",
                Minecraft.getInstance().getTextureManager(),
                RenderState.KSG_LAYER_0, null,
                RenderState.KSG_NORMAL_MAP, rl -> RenderState.createDefaultSprite(rl,
                () -> RenderState.getSpecularMapDefaultImage(16 ,16)),
                (rl, w, h) -> RenderState.createDefaultSprite(rl,
                        () -> RenderState.getNormalMapDefaultImage(w, h)),
                RenderState.KSG_METALLIC_MAP, rl -> RenderState.createDefaultSprite(rl,
                        () -> RenderState.getSpecularMapDefaultImage(16 ,16)),
                (rl, w, h) -> RenderState.createDefaultSprite(rl,
                        () -> RenderState.getSpecularMapDefaultImage(w, h))
        );

        FileTextureSource fileTextureSource = new FileTextureSource("file");
        JarTextureSource jarTextureSource = new JarTextureSource("jar");
        BufferedImageTextureSource bufferedImageTextureSource = new BufferedImageTextureSource("buffered_image");

        basic.registerSource(fileTextureSource);
        basic.registerSource(jarTextureSource);
        basic.registerSource(bufferedImageTextureSource);

        TEXTURE_BASIC = basic;

        KasugaModelManager modelManager = new KasugaModelManager(List.of(basic));
        JsonModelSourceManager jsonSource = new JsonModelSourceManager("json");
        StrModelSourceManager strSource = new StrModelSourceManager("str");
        ZipModelSourceManager zipSource = new ZipModelSourceManager("zip");

        KasugaPipeLineRouter router = new KasugaPipeLineRouter();
        router.registerByExtension(".geo.json", () -> BE_PIPELINE);
        router.registerByExtension(".obj", () -> OBJ_PIPELINE);
        router.registerByExtension(".mmd.zip", () -> MMD_PIPELINE);
        router.registerByExtension(".bbmodel", () -> BBMODEL_PIPELINE);
        router.registerByExtension(".json", () -> JE_PIPELINE);
        modelManager.registerRouter(router);
        modelManager.registerModelScanner(new KasugaModelScanner());

        event.registerReloadListener(modelManager);

        FileJsonModelSource fileJsonModelSource = new FileJsonModelSource("file_json");
        JarJsonModelSource jarJsonModelSource = new JarJsonModelSource("jar_json");
        FileStrModelSource fileStrModelSource = new FileStrModelSource("file_str");
        JarStrModelSource jarStrModelSource = new JarStrModelSource("jar_str");
        FileZipModelSource fileZipModelSource = new FileZipModelSource("file_zip");
        JarZipModelSource jarZipModelSource = new JarZipModelSource("jar_zip");


        jsonSource.registerSource(fileJsonModelSource);
        jsonSource.registerSource(jarJsonModelSource);
        strSource.registerSource(fileStrModelSource);
        strSource.registerSource(jarStrModelSource);
        zipSource.registerSource(fileZipModelSource);
        zipSource.registerSource(jarZipModelSource);

        BEModelLoader loader = new BEModelLoader("be_model", KasugaLib.MODID);
        MCBridge mcBridge = new MCBridge();
        MCBackend mcBackend = new MCBackend();
        MC_BACKEND = mcBackend;

        BE_PIPELINE = new ModelPipeLine.Builder<JsonObject, KsgVertexBuffer, ResourceLocation,
                ResourceLocation, String>()
                .withModelSource(jsonSource)
                .withSidedSource(basic.getType(), "mc_layer_0", basic)
                .withLoader(loader)
                .withBridge("mc_bridge", mcBridge)
                .withBackend("mc_backend", mcBackend)
                .build();

        JE_PIPELINE = new ModelPipeLine.Builder<JsonObject, KsgVertexBuffer, ResourceLocation,
                ResourceLocation, String >()
                .withModelSource(jsonSource)
                .withSidedSource(basic.getType(), "mc_layer_0", basic)
                .withLoader(new JEModelLoader("je_model"))
                .withBridge("mc_bridge", mcBridge)
                .withBackend("mc_backend", mcBackend)
                .build();

        OBJ_PIPELINE = new ModelPipeLine.Builder<String, KsgVertexBuffer, ResourceLocation,
                ResourceLocation, String>().withModelSource(strSource)
                .withSidedSource(basic.getType(), "mc_layer_0", basic)
                .withLoader(new KsgObjLoader("obj_model"))
                .withBridge("mc_bridge", mcBridge)
                .withBackend("mc_backend", mcBackend)
                .build();

        BBMODEL_PIPELINE = new ModelPipeLine.Builder<String, KsgVertexBuffer, ResourceLocation,
                ResourceLocation, Integer>().withModelSource(strSource)
                .withSidedSource(basic.getType(), "mc_layer_0", basic)
                .withLoader(new KsgBbModelLoader("bbmodel"))
                .withBridge("mc_bridge", mcBridge)
                .withBackend("mc_backend", mcBackend)
                .build();

        PMX_LOADER = new KsgPmxLoader("pmx_model");

        MMD_PIPELINE = new ModelPipeLine.Builder<ZipHelper, KsgVertexBuffer, ResourceLocation, ResourceLocation, ZipResource>()
                .withModelSource(zipSource)
                .withSidedSource(basic.getType(), "mc_layer_0", basic)
                .withLoader(PMX_LOADER)
                .withBridge("mc_bridge", mcBridge)
                .withBackend("mc_backend", mcBackend)
                .build();
    }

    @SubscribeEvent
    public static void onShaderRegister(RegisterShadersEvent event) {
        ResourceProvider provider = event.getResourceProvider();
        try {
            ShaderInstance shaderInstance = new KasugaShaderInstance(
                    provider, ResourceLocation.tryBuild("kasuga_lib", "ksglib_main"),
                    UML_VERTEX_FORMAT
            );
            event.registerShader(shaderInstance, instance -> RenderState.UML_SHADER_INSTANCE = instance);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader 'ksglib_main'", e);
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderBuffers(RegisterRenderBuffersEvent event) {
        RenderType typeDefault = RenderType.create(
                "kasuga_lib:uml_render_type",
                UML_VERTEX_FORMAT,
                VertexFormat.Mode.QUADS,
                64 * 1024 * 1024,
                true,
                true,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderState.UML_TEXTURE_STATE)
                        .setShaderState(RenderState.UML_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false)
        );
        RenderState.RENDER_TYPE = typeDefault;

        RenderType typeIris = RenderType.create(
                "kasuga_lib:iris_compat_render_type",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                64 * 1024 * 1024,
                true,
                true,
                RenderType.CompositeState.builder()
                        .setTextureState(RenderState.UML_TEXTURE_STATE)
                        .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(false)
        );
        RenderState.IRIS_COMPAT_RENDER_TYPE = typeIris;

        event.registerRenderBuffer(typeDefault);
        event.registerRenderBuffer(typeIris);
    }

    @SubscribeEvent
    public static void onRenderTypeRegister(RegisterNamedRenderTypesEvent event) {
        AccessorOnRegisterRenderTypesEvent accessor = (AccessorOnRegisterRenderTypesEvent) event;
        accessor.getRenderTypes().put(
                RenderState.KSG_RENDER_TYPE, new RenderTypeGroup(
                        RenderState.RENDER_TYPE,
                        RenderState.RENDER_TYPE,
                        RenderState.RENDER_TYPE
                )
        );
        accessor.getRenderTypes().put(
                RenderState.KSG_IRIS_RENDER_TYPE, new RenderTypeGroup(
                        RenderState.IRIS_COMPAT_RENDER_TYPE,
                        RenderState.IRIS_COMPAT_RENDER_TYPE,
                        RenderState.IRIS_COMPAT_RENDER_TYPE
                )
        );

//        event.register(RenderState.KSG_RENDER_TYPE, RenderState.RENDER_TYPE, RenderState.RENDER_TYPE);
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Camera camera = event.getCamera();
        Frustum frustum = event.getFrustum();
        Matrix4f modelViewMatrix = event.getModelViewMatrix();
        Matrix4f projectionMatrix = event.getProjectionMatrix();
        PoseStack poseStack = event.getPoseStack();
        int renderTick = event.getRenderTick();
        DeltaTracker partial = event.getPartialTick();
        RenderBuffers renderBuffers = Minecraft.getInstance().renderBuffers();
        MultiBufferSource.BufferSource source = renderBuffers.bufferSource();
        VertexConsumer consumer = source.getBuffer(RenderState.getRenderType());
        MCBackendContext context = new MCBackendContext(
                consumer, poseStack, renderBuffers,
                source, camera, frustum, modelViewMatrix,
                projectionMatrix, renderTick, partial, Minecraft.getInstance().level
        );
        testModel();
        poseStack.pushPose();
        Vec3 pos = camera.getPosition();
        poseStack.translate(- pos.x(), - pos.y(), - pos.z());
        // TODO: 这里放置各Backend
        MC_BACKEND.renderAllObjects(context);
        // TODO: 最后结束该批次
        poseStack.popPose();
        source.endBatch(RenderState.RENDER_TYPE);
    }

    private static void testModel() {
        testBbModels();
    }

    private static void testBbModels() {
        if (BBMODEL_PIPELINE == null || Minecraft.getInstance().player == null) return;
        Vec3 playerPosition = Minecraft.getInstance().player.position();
        Vec3 lookDirection = Minecraft.getInstance().player.getLookAngle();
        Vec3 headPosition = playerPosition.add(lookDirection.scale(3.0)).add(0.0, 1.0, 0.0);
        Vec3 bogeyPosition = playerPosition.add(lookDirection.scale(8.0)).add(0.0, 1.0, 0.0);
        testBbModel(
                "df11g_head.bbmodel",
                "test_bbmodel_head",
                new Transform().translate((float) headPosition.x, (float) headPosition.y, (float) headPosition.z)
        );
        testBbModel(
                "qj_bogey_main.bbmodel",
                "test_bbmodel_bogey",
                new Transform().translate((float) bogeyPosition.x, (float) bogeyPosition.y, (float) bogeyPosition.z)
        );
    }

    private static void testBbModel(String fileName, String instanceName, Transform transform) {
        ResourceLocation model = ResourceLocation.fromNamespaceAndPath(
                KasugaLib.MODID,
                "models/block/test/blockbench/" + fileName
        );
        ResourceLocation instance = ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, instanceName);
        if (!BBMODEL_PIPELINE.hasModel(model)) {
            BBMODEL_PIPELINE.loadModel(model, null);
        }
        if (BBMODEL_PIPELINE.hasInstance(model, instance)) return;
        BBMODEL_PIPELINE.createInstance(model, instance, transform, null, null);
        BBMODEL_PIPELINE.addToRenderer(model, instance, "mc_bridge", "mc_backend");
    }

    public static void testMMD() {
        String fileName1 = "test.mmd.zip";
        String fileName2 = "test2.mmd.zip";
        String fileName3 = "test3.mmd.zip";
        String name = "公式服111.pmx";
        String name2 = "OL制服弱音盘发.pmx";
        String name3 = "OL制服弱音散发.pmx";
        String name4 = "tda bunny miku 2.0.pmx";
        testMMD(fileName1, name, "test_mmd");
//        testMMD(fileName3, name4, "test_mmd_4");
    }

    public static void testUI() {
        if (UI_BACKEND.getRenderables().isEmpty()) {
            Screen screen = new AlertScreen(() -> {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("触发了按钮"), true
                );
            }, Component.literal("Test"), Component.literal("This is a test"));
            screen.init(Minecraft.getInstance(), Minecraft.getInstance().getWindow().getGuiScaledWidth(), Minecraft.getInstance().getWindow().getGuiScaledHeight());
            UI_BACKEND.addRenderable(screen);
        }
        GuiGraphics guiGraphics = UIBackend.constructGuiGraphics();
        UI_BACKEND.renderAllUis(guiGraphics, 0);
    }

    public static void testMMD(String fileName, String modelName, String instanceName) {
        ResourceLocation rl = PMX_LOADER.getLocByFileAndName(
                ResourceLocation.tryBuild("kasuga_lib", "models/pmx/" + fileName),
                modelName
        );
        if (rl == null) return;
        ResourceLocation instanceLoc = ResourceLocation.tryBuild("kasuga_lib", instanceName);
        if (MMD_PIPELINE.hasInstance(rl, instanceLoc)) {
//            currentInstance.getSkeletonInstance().rotateRoot(QuaternionHelper.fromXYZAngle(0, 1f, 0, true));
            Bone rootBone = currentInstance.getSkeletonInstance().getSkeleton().getRoot();
            currentInstance.getSkeletonInstance().rotate(rootBone, QuaternionHelper.fromXYZAngle(0, 1f, 0, true));
            return;
        }
        currentInstance = MMD_PIPELINE.createInstance(rl, instanceLoc, null, null, null);
        MMD_PIPELINE.addToRenderer(rl, instanceLoc, "mc_bridge", "mc_backend");
    }

    public static void testObj() {
        ResourceLocation loc =  ResourceLocation.tryBuild("kasuga_lib", "models/obj/df5_frame.obj");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild("kasuga_lib", "test_wheel");
        if (OBJ_PIPELINE.hasInstance(loc, instanceLoc)) return;
        OBJ_PIPELINE.createInstance(loc, instanceLoc, null, null, null);
        OBJ_PIPELINE.addToRenderer(loc, instanceLoc, "mc_bridge", "mc_backend");
    }

    public static void testBe() {
        ResourceLocation loc = ResourceLocation.tryBuild("kasuga_lib", "geometry.unknown");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild("kasuga_lib", "test_model");
        if (BE_PIPELINE.hasInstance(loc, instanceLoc)) return;
        BE_PIPELINE.createInstance(loc, instanceLoc, null, null, null);
        BE_PIPELINE.addToRenderer(loc, instanceLoc, "mc_bridge", "mc_backend");
    }

    public static void testJe() {
        ResourceLocation locA = ResourceLocation.tryBuild("kasuga_lib", "models/je/test_parent_a.json");
        ResourceLocation locB = ResourceLocation.tryBuild("kasuga_lib", "models/je/test_parent_b.json");
        ResourceLocation instanceA = ResourceLocation.tryBuild("kasuga_lib", "test_je_a");
        ResourceLocation instanceB = ResourceLocation.tryBuild("kasuga_lib", "test_je_b");

        if (!JE_PIPELINE.hasInstance(locA, instanceA)) {
            JE_PIPELINE.createInstance(locA, instanceA, null, null, null);
            JE_PIPELINE.addToRenderer(locA, instanceA, "mc_bridge", "mc_backend");
        }
        if (!JE_PIPELINE.hasInstance(locB, instanceB)) {
            Transform offsetB = new Transform().translate(2, 0, 0);
            JE_PIPELINE.createInstance(locB, instanceB, offsetB, null, null);
            JE_PIPELINE.addToRenderer(locB, instanceB, "mc_bridge", "mc_backend");
        }
    }
}
