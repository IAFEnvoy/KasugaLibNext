package lib.kasuga.rendering.models.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.client.loading.LoadingIndicator;
import lib.kasuga.mixins.client.AccessorOnRegisterRenderTypesEvent;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.WorldRenderPipelineDispatcher;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.RenderPipelineScope;
import lib.kasuga.rendering.effect.builtin.BillboardEffects;
import lib.kasuga.rendering.effect.builtin.blackhole.BlackHoleEffects;
import lib.kasuga.rendering.effect.debug.EffectDiagnosticsScreen;
import lib.kasuga.rendering.effect.debug.ShaderParameterCommands;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderPreparationScheduler;
import lib.kasuga.rendering.effect.shader.ShaderParameterPersistence;
import lib.kasuga.rendering.models.mc.backend.*;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.source.model.*;
import lib.kasuga.rendering.models.mc.source.texture.BufferedImageTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.source.texture.FileTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.JarTextureSource;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeCoordinator;
import lib.kasuga.rendering.models.mc.source.texture.bake.PbrBakeState;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.event.*;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static lib.kasuga.rendering.models.mc.backend.RenderState.UML_VERTEX_FORMAT;

@EventBusSubscriber(value = Dist.CLIENT)
public class Constants {

    public static CombinedTextureManager TEXTURE_BASIC;
    public static SourceType TEXTURE_TYPE, MODEL_TYPE;
    public static UIBackend UI_BACKEND;

    public static ModelInstance currentInstance;
    private static final RenderPipelineScope CLIENT_RENDER_PIPELINES = RenderPipelineScope.create(
            ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "client_render_lifetime")
    );
    private static boolean renderPipelineRegistrationPosted;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ShaderPreparationScheduler.configureFromSystemProperty();
        ShaderPreparationScheduler.prestartWorkers();
        ShaderParameterPersistence.initialize();
        if (UI_BACKEND == null) {
            UI_BACKEND = new UIBackend();
        }
        if (!renderPipelineRegistrationPosted) {
            renderPipelineRegistrationPosted = true;
            ModLoader.postEvent(new RegisterRenderPipelinesEvent(CLIENT_RENDER_PIPELINES));
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        RenderPipelineRegistrar pipelines = event.registrar(
                ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "builtin_rendering")
        );
        BillboardEffects.initialize(pipelines);
        BlackHoleEffects.initialize(pipelines);
        RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "models"),
                        RenderPhase.AFTER_ENTITIES
                )
                .priority(0)
                .build();
        pipelines.world(descriptor, Constants::renderModels);
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

        PipelineRegistry.registerBuiltins(basic);

        KasugaPipeLineRouter router = new KasugaPipeLineRouter();
        PipelineRegistry.registerDefaultRoutes(router);

        KasugaModelManager modelManager = new KasugaModelManager(List.of(basic));
        modelManager.registerRouter(router);
        modelManager.registerModelScanner(new KasugaModelScanner());

        event.registerReloadListener(modelManager);
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
        RenderShaderRegistry.registerShaders(event);
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
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_effects")
                .executes(context -> {
                    Minecraft.getInstance().execute(EffectDiagnosticsScreen::open);
                    return 1;
                })
                .then(Commands.literal("preload").executes(context -> {
                    int queued = RenderShaderRegistry.preloadPending();
                    context.getSource().sendSuccess(
                            () -> Component.literal("Queued " + queued + " Kasuga shaders for preload"),
                            false
                    );
                    return queued;
                }))
                .then(Commands.literal("workers")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    int requested = IntegerArgumentType.getInteger(context, "count");
                                    ShaderPreparationScheduler.configureWorkers(requested);
                                    int workers = ShaderPreparationScheduler.workerCount();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Shader preparation workers: " + workers
                                                    + (requested == 0 ? " (automatic)" : " (requested "
                                                    + requested + ")")),
                                            false
                                    );
                                    return workers;
                                })))
                .then(ShaderParameterCommands.command()));
        event.getDispatcher().register(Commands.literal("kasuga_pbr")
                .then(Commands.literal("status").executes(context -> {
                    Map<String, PbrBakeState> states = PbrBakeCoordinator.getInstance().states();
                    long ready = states.values().stream().filter(state -> state == PbrBakeState.READY).count();
                    long failed = states.values().stream().filter(state -> state == PbrBakeState.FAILED).count();
                    PbrBakeCoordinator.PbrBakeStats stats = PbrBakeCoordinator.getInstance().stats();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga PBR: " + ready + " ready, " + failed + " failed, " + states.size()
                                    + " total; GPU=" + stats.gpuBakes() + ", CPU=" + stats.cpuBakes()
                                    + ", disk-cache=" + stats.cacheHits() + ", memory-cache=" + stats.memoryHits()
                                    + ", failed=" + stats.failures() + ", requests=" + stats.requests()
                                    + "; key=" + formatPbrMillis(stats.cacheKeyNanos())
                                    + ", read=" + formatPbrMillis(stats.diskReadNanos())
                                    + ", GPU-time=" + formatPbrMillis(stats.gpuBakeNanos())
                                    + ", CPU-time=" + formatPbrMillis(stats.cpuBakeNanos())
                                    + ", write=" + formatPbrMillis(stats.diskWriteNanos())
                    ), false);
                    return 1;
                }))
                .then(Commands.literal("rebake").executes(context -> {
                    try {
                        PbrBakeCoordinator.getInstance().clearCache();
                    } catch (IOException exception) {
                        context.getSource().sendFailure(Component.literal(
                                "Failed to clear Kasuga PBR cache: " + exception.getMessage()
                        ));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga PBR cache cleared; reloading resources"
                    ), false);
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
                    return 1;
                }))
                .then(Commands.literal("reload_config").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Reloading Kasuga PBR conversion config and resources"
                    ), false);
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
                    return 1;
                })));
    }

    private static String formatPbrMillis(long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
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
        WorldRenderPipelineDispatcher.dispatch(event);
    }

    private static void renderModels(WorldRenderPipelineContext pipelineContext) {
        // Resource reload builds models before their new atlas sprites exist.
        // Keep the currently published generation untouched and skip custom
        // rendering until textures and models are atomically swapped.
        if (LoadingIndicator.snapshot().active()) return;
        MCBackend mcBackend = PipelineRegistry.backend();
        if (mcBackend == null) return;

        PoseStack poseStack = pipelineContext.poseStack();
        RenderBuffers renderBuffers = pipelineContext.renderBuffers();
        MultiBufferSource.BufferSource source = pipelineContext.bufferSource();
        RenderType renderType = RenderState.getRenderType();
        VertexConsumer consumer = source.getBuffer(renderType);
        MCBackendContext context = new MCBackendContext(
                consumer, poseStack, renderBuffers,
                source, pipelineContext.camera(), pipelineContext.frustum(), pipelineContext.modelViewMatrix(),
                pipelineContext.projectionMatrix(), pipelineContext.renderTick(), pipelineContext.partialTick(),
                pipelineContext.level()
        );
        Vec3 pos = pipelineContext.camera().getPosition();
        poseStack.translate(- pos.x(), - pos.y(), - pos.z());
        try {
            testModel();
            mcBackend.renderAllObjects(context);
        } finally {
            source.endBatch(renderType);
        }
    }

    private static void testModel() {
        testMMD();
//        testUI();
//        testObj();
//        testBe();
//        testJe();
    }

    public static void testMMD() {
        String fileName1 = "test.mmd.zip";
        String fileName2 = "test2.mmd.zip";
        String fileName3 = "test3.mmd.zip";
        String name = "PS - Classical Butterfly Kanade.pmx";
        String name2 = "OL制服弱音盘发.pmx";
        String name3 = "OL制服弱音散发.pmx";
        String name4 = "tda bunny miku 2.0.pmx";
        String name5 = "unfading_flowers_miku_black.pmx";
        testMMD(fileName2, name5, "test_mmd");
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
        ResourceLocation rl = PipelineRegistry.pmxLoader().getLocByFileAndName(
                ResourceLocation.tryBuild("kasuga_lib", "models/pmx/" + fileName),
                modelName
        );
        if (rl == null) return;
        ResourceLocation instanceLoc = ResourceLocation.tryBuild("kasuga_lib", instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> mmd = PipelineRegistry.pmx();
        if (mmd.hasInstance(rl, instanceLoc)) {
//            currentInstance.getSkeletonInstance().rotateRoot(QuaternionHelper.fromXYZAngle(0, 1f, 0, true));
            Bone rootBone = currentInstance.getSkeletonInstance().getSkeleton().getRoot();
//            currentInstance.getSkeletonInstance().rotate(rootBone, QuaternionHelper.fromXYZAngle(0, 1f, 0, true));
            return;
        }
        currentInstance = mmd.createInstance(rl, instanceLoc, null, null, null);
        mmd.addToRenderer(rl, instanceLoc, "mc_bridge", "mc_backend");
    }

    public static void testObj() {
        ResourceLocation loc =  ResourceLocation.tryBuild("kasuga_lib", "models/obj/df5_frame.obj");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild("kasuga_lib", "test_wheel");
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> obj = PipelineRegistry.obj();
        if (obj.hasInstance(loc, instanceLoc)) return;
        obj.createInstance(loc, instanceLoc, null, null, null);
        obj.addToRenderer(loc, instanceLoc, "mc_bridge", "mc_backend");
    }

    public static void testBe() {
        ResourceLocation loc = ResourceLocation.tryBuild("kasuga_lib", "geometry.unknown");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild("kasuga_lib", "test_model");
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> be = PipelineRegistry.be();
        if (be.hasInstance(loc, instanceLoc)) return;
        be.createInstance(loc, instanceLoc, null, null, null);
        be.addToRenderer(loc, instanceLoc, "mc_bridge", "mc_backend");
    }

    public static void testJe() {
        ResourceLocation locA = ResourceLocation.tryBuild("kasuga_lib", "models/je/test_parent_a.json");
        ResourceLocation locB = ResourceLocation.tryBuild("kasuga_lib", "models/je/test_parent_b.json");
        ResourceLocation instanceA = ResourceLocation.tryBuild("kasuga_lib", "test_je_a");
        ResourceLocation instanceB = ResourceLocation.tryBuild("kasuga_lib", "test_je_b");

        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> je = PipelineRegistry.je();
        if (!je.hasInstance(locA, instanceA)) {
            je.createInstance(locA, instanceA, null, null, null);
            je.addToRenderer(locA, instanceA, "mc_bridge", "mc_backend");
        }
        if (!je.hasInstance(locB, instanceB)) {
            Transform offsetB = new Transform().translate(2, 0, 0);
            je.createInstance(locB, instanceB, offsetB, null, null);
            je.addToRenderer(locB, instanceB, "mc_bridge", "mc_backend");
        }
    }
}
