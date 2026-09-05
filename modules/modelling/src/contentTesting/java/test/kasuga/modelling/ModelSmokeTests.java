package test.kasuga.modelling;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;

/** Client-only smoke models included exclusively in the contentTesting source set. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelSmokeTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEST_MMD_PHYSICS = ResourceLocation.fromNamespaceAndPath(
            KasugaLib.MODID, "ragdolls/tda_bunny_miku.json");
    private static boolean missingMmdLogged;
    private static boolean missingBbmodelLogged;
    private static ModelInstance currentMmdInstance;

    private ModelSmokeTests() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) return;
        testModel();
    }

    private static void testModel() {
        if (!Boolean.parseBoolean(System.getProperty("kasuga.renderTestModels", "true"))) return;
        switch (System.getProperty("kasuga.testModel", "bbmodel").toLowerCase(Locale.ROOT)) {
            case "bbmodel" -> testBbModels();
            case "obj" -> testObj();
            case "be" -> testBe();
            case "je" -> testJe();
            case "ling", "ling_singer" -> testLingSinger();
            default -> testMmd();
        }
    }

    private static void testBbModels() {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 playerPosition = minecraft.player.position();
        Vec3 lookDirection = minecraft.player.getLookAngle();
        String selected = System.getProperty("kasuga.testBbmodel");
        if (selected != null && !selected.isBlank()) {
            Vec3 position = playerPosition.add(lookDirection.scale(3.0)).add(0.0, 1.0, 0.0);
            testBbmodel(normalizeBbmodelPath(selected), "test_bbmodel", position);
            return;
        }

        Vec3 headPosition = playerPosition.add(lookDirection.scale(3.0)).add(0.0, 1.0, 0.0);
        Vec3 bogeyPosition = playerPosition.add(lookDirection.scale(8.0)).add(0.0, 1.0, 0.0);
        testBbmodel("models/block/test/blockbench/df11g_head.bbmodel", "test_bbmodel_head", headPosition);
        testBbmodel("models/block/test/blockbench/qj_bogey_main.bbmodel", "test_bbmodel_bogey", bogeyPosition);
    }

    private static String normalizeBbmodelPath(String path) {
        String trimmed = path.trim().replace('\\', '/');
        if (trimmed.contains(":")) return trimmed;
        return trimmed.startsWith("models/") ? trimmed : "models/block/test/blockbench/" + trimmed;
    }

    private static void testBbmodel(String modelPath, String instanceName, Vec3 position) {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, modelPath);
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.bbmodel();
        if (!pipeline.hasModel(modelLoc)) {
            if (!missingBbmodelLogged) {
                missingBbmodelLogged = true;
                LOGGER.warn("Test bbmodel '{}' is unavailable after resource reload; check contentTesting assets/{}/models/model_proxy.json and restart the client",
                        modelLoc, KasugaLib.MODID);
            }
            return;
        }
        missingBbmodelLogged = false;
        if (pipeline.hasInstance(modelLoc, instanceLoc)) return;
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, new Transform().translate(
                (float) position.x, (float) position.y, (float) position.z), null, null);
        if (instance != null) pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
    }

    private static void testMmd() {
        testMmdModel("test3.mmd.zip", "tda bunny miku 2.0.pmx", "test_mmd", new Transform(), true);
        testLingSinger();
    }

    private static void testLingSinger() {
        testMmdModel("ling_singer.mmd.zip", "ling_singer.pmx", "test_mmd_ling_singer",
                new Transform().translate(2.5f, 0.0f, 0.0f), false);
    }

    private static void testMmdModel(String fileName, String modelName, String instanceName,
                                     Transform rootTransform, boolean enablePhysics) {
        ResourceLocation modelLoc = PipelineRegistry.pmxLoader().getLocByFileAndName(
                ResourceLocation.tryBuild(KasugaLib.MODID, "models/pmx/" + fileName), modelName);
        if (modelLoc == null) {
            if (!missingMmdLogged) {
                missingMmdLogged = true;
                LOGGER.warn("Test MMD model '{}' is unavailable after resource reload; check contentTesting models/model_proxy.json",
                        modelName);
            }
            return;
        }
        missingMmdLogged = false;
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.pmx();
        if (pipeline.hasInstance(modelLoc, instanceLoc)) {
            if (enablePhysics) attachMmdPhysics(pipeline.getInstance(modelLoc, instanceLoc));
            return;
        }
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, rootTransform, null, null);
        if (instance == null) return;
        if ("ling_singer.pmx".equals(modelName)) applyLingSingerTpose(instance);
        if (enablePhysics) attachMmdPhysics(instance);
        pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
    }

    /** The supplied Ling Singer PMX is authored in a relaxed pose; keep this smoke instance static and T-shaped. */
    private static void applyLingSingerTpose(ModelInstance instance) {
        float shoulderAngle = (float) Math.toRadians(38.65f);
        var skeleton = instance.getSkeletonInstance();
        boolean right = skeleton.rotate("右肩P", new Quaternionf().rotateZ(-shoulderAngle));
        boolean left = skeleton.rotate("左肩P", new Quaternionf().rotateZ(shoulderAngle));
        instance.updateImmediate();
        LOGGER.info("Applied static Ling Singer T-pose (right shoulder={}, left shoulder={})", right, left);
    }

    private static void attachMmdPhysics(ModelInstance instance) {
        if (instance == null || currentMmdInstance == instance) return;
        currentMmdInstance = instance;
        if (!Boolean.parseBoolean(System.getProperty("kasuga.testModelPhysics", "true"))) return;
        try {
            MinecraftRagdollConfig config = MinecraftRagdollConfig.load(
                    Minecraft.getInstance().getResourceManager(), TEST_MMD_PHYSICS);
            config.attach(instance, () -> Minecraft.getInstance().level,
                    Boolean.parseBoolean(System.getProperty("kasuga.testModelPhysicsDrop", "true")));
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to attach test ragdoll config {}", TEST_MMD_PHYSICS, exception);
        }
    }

    private static void testObj() {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "models/obj/df5_frame.obj");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "test_wheel");
        addStaticInstance(PipelineRegistry.obj(), modelLoc, instanceLoc, null);
    }

    private static void testBe() {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "geometry.unknown");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "test_model");
        addStaticInstance(PipelineRegistry.be(), modelLoc, instanceLoc, null);
    }

    private static void testJe() {
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.je();
        addStaticInstance(pipeline, ResourceLocation.tryBuild(KasugaLib.MODID, "models/je/test_parent_a.json"),
                ResourceLocation.tryBuild(KasugaLib.MODID, "test_je_a"), null);
        addStaticInstance(pipeline, ResourceLocation.tryBuild(KasugaLib.MODID, "models/je/test_parent_b.json"),
                ResourceLocation.tryBuild(KasugaLib.MODID, "test_je_b"), new Transform().translate(2, 0, 0));
    }

    private static void addStaticInstance(ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline,
                                          ResourceLocation modelLoc, ResourceLocation instanceLoc, Transform transform) {
        if (!pipeline.hasModel(modelLoc) || pipeline.hasInstance(modelLoc, instanceLoc)) return;
        if (pipeline.createInstance(modelLoc, instanceLoc, transform, null, null) != null) {
            pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
        }
    }
}
