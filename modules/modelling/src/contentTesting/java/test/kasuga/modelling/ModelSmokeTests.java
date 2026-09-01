package test.kasuga.modelling;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollRuntime;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationPlayer;
import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdReader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Optional;

/** Client-only smoke models included exclusively in the contentTesting source set. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelSmokeTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEST_MMD_PHYSICS = ResourceLocation.fromNamespaceAndPath(
            KasugaLib.MODID, "ragdolls/tda_bunny_miku.json");
    /** だいあるのーと / 爱丽丝版 (绝区零 base rig, matches 铃's bones 58/58). */
    private static final ResourceLocation LING_VMD = ResourceLocation.fromNamespaceAndPath(
            KasugaLib.MODID, "animations/vmd/motion_dialnotebook_alice.vmd");
    /** kiss-me-more (MMD dance motion) — 105/105 core humanoid bones match 银狼. */
    private static final ResourceLocation SILVER_WOLF_VMD = ResourceLocation.fromNamespaceAndPath(
            KasugaLib.MODID, "animations/vmd/kiss_me_more.vmd");
    private static boolean missingMmdLogged;
    private static boolean missingBbmodelLogged;
    private static ModelInstance currentMmdInstance;
    private static AnimationPlayer<VmdMotion> lingPlayer;
    private static AnimationPlayer<VmdMotion> silverWolfPlayer;

    private ModelSmokeTests() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) return;
        testModel();
        advancePlayer(lingPlayer);
        advancePlayer(silverWolfPlayer);
        logSilverWolfLegDiagnostics();
    }

    /** 诊断：每 10 tick 打印银狼左腿膝关节/踝关节状态，用于核对游戏内 IK 是否生效。 */
    private static int diagTickCounter;

    private static void logSilverWolfLegDiagnostics() {
        if (silverWolfPlayer == null || !silverWolfPlayer.isPlaying()) return;
        if (++diagTickCounter % 10 != 0) return;
        ModelInstance instance = silverWolfPlayer.model();
        SkeletonInstance skel = instance.getSkeletonInstance();
        Bone knee = skel.getSkeleton().getBoneMap().get("左ひざ");
        Bone ankle = skel.getSkeleton().getBoneMap().get("左足首");
        Bone target = skel.getSkeleton().getBoneMap().get("左足ＩＫ");
        Bone center = skel.getSkeleton().getBoneMap().get("センター");
        if (knee == null || ankle == null || target == null) return;
        Transform kneeEval = skel.getEvaluatedTransforms().get(knee);
        Transform ankleAbs = skel.getAbsoluteTransforms().get(ankle);
        Transform targetAbs = skel.getAbsoluteTransforms().get(target);
        Transform centerAbs = center == null ? null : skel.getAbsoluteTransforms().get(center);
        Bone hipB = skel.getSkeleton().getBoneMap().get("左足");
        Bone lowerBodyB = skel.getSkeleton().getBoneMap().get("下半身");
        Bone qzB = skel.getSkeleton().getBoneMap().get("QZ_0_0");
        if (kneeEval == null || ankleAbs == null || targetAbs == null) return;
        Vector3f kneeEuler = kneeEval.getRotation().getEulerAnglesXYZ(new Vector3f());
        float reach = ankleAbs.getPosition().distance(new Vector3f(targetAbs.getPosition()));
        String centerPos = centerAbs == null ? "?" :
                String.format("(%.2f,%.2f,%.2f)", centerAbs.getPosition().x, centerAbs.getPosition().y, centerAbs.getPosition().z);
        String targetPos = String.format("(%.2f,%.2f,%.2f)", targetAbs.getPosition().x, targetAbs.getPosition().y, targetAbs.getPosition().z);
        String anklePos = String.format("(%.2f,%.2f,%.2f)", ankleAbs.getPosition().x, ankleAbs.getPosition().y, ankleAbs.getPosition().z);
        // 决定性三要素：髋位置（腿悬垂源头）、IK 膝盖修正（IK 是否在解）、飘带骨位置（是否跟随模型）
        Transform hipAbs = hipB == null ? null : skel.getAbsoluteTransforms().get(hipB);
        Transform lowerAbs = lowerBodyB == null ? null : skel.getAbsoluteTransforms().get(lowerBodyB);
        Transform qzAbs = qzB == null ? null : skel.getAbsoluteTransforms().get(qzB);
        String hipStr = hipAbs == null ? "?" : String.format("(%.2f,%.2f,%.2f)",
                hipAbs.getPosition().x, hipAbs.getPosition().y, hipAbs.getPosition().z);
        String lowerStr = lowerAbs == null ? "?" : String.format("(%.2f,%.2f,%.2f)",
                lowerAbs.getPosition().x, lowerAbs.getPosition().y, lowerAbs.getPosition().z);
        String qzStr = qzAbs == null ? "?" : String.format("(%.2f,%.2f,%.2f)",
                qzAbs.getPosition().x, qzAbs.getPosition().y, qzAbs.getPosition().z);
        // bind 对比：如果 bind 踝≈0.10 而 abs 踝≈0.80 → 输入正确、求解发散；若 bind 本身≈0.80 → loader/scale 问题
        Vector3f bindAnkle = ankle.getTransform().getPosition();
        Vector3f bindHip = hipB == null ? null : hipB.getTransform().getPosition();
        String bindStr = String.format("bind踝=(%.2f,%.2f,%.2f) bind髋=(%.2f,%.2f,%.2f)",
                bindAnkle.x, bindAnkle.y, bindAnkle.z,
                bindHip == null ? 0 : bindHip.x, bindHip == null ? 0 : bindHip.y, bindHip == null ? 0 : bindHip.z);
        LOGGER.info("[leg-diag] 膝弯={}° 踝→目标={} センター={} 髋={} 下半身={} 目标={} 踝={} QZ_0_0={} {}",
                Math.round(Math.toDegrees(kneeEuler.x) * 10.0) / 10.0,
                Math.round(reach * 100.0) / 100.0, centerPos, hipStr, lowerStr, targetPos, anklePos,
                qzStr, bindStr);
    }

    private static void advancePlayer(AnimationPlayer<VmdMotion> player) {
        if (player != null && player.isPlaying()) {
            player.model().animate(1f / 20f);
        }
    }

    private static void testModel() {
        if (!Boolean.parseBoolean(System.getProperty("kasuga.renderTestModels", "true"))) return;
        switch (System.getProperty("kasuga.testModel", "silverwolf").toLowerCase(Locale.ROOT)) {
            case "bbmodel" -> testBbModels();
            case "obj" -> testObj();
            case "be" -> testBe();
            case "je" -> testJe();
            case "ling" -> testLing();
            case "silverwolf" -> testSilverWolf();
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
        ModelInstance instance = createMmdInstance("test3.mmd.zip", "tda bunny miku 2.0.pmx", "test_mmd");
        if (instance != null) attachMmdPhysics(instance, "tda bunny miku 2.0.pmx");
    }

    /**
     * 铃 (Zenless Zone Zero) — PMX packed as a UTF-8-named .mmd.zip, then a VMD clip is played on it
     * through the {@link AnimationPlayer} / {@link VmdSampler} pipeline (VMD → Pose → PoseSink).
     */
    private static void testLing() {
        ModelInstance instance = createMmdInstance("ling3.mmd.zip", "铃.pmx", "test_ling");
        if (instance == null) return;
        attachMmdPhysics(instance, "铃.pmx");
        lingPlayer = playVmdClip(instance, lingPlayer, LING_VMD,
                new VmdSampler(new Vector3f(1f / 12f)), "ling");
    }

    /**
     * 银狼 (Honkai: Star Rail). Bone-compat checked against kiss-me-more.vmd before packaging:
     * 105/105 core humanoid bones (センター/上半身/下半身/腰/首/頭/両腕両手/両脚両足/全手指/IK/両目/メガネ)
     * match the PMX; the VMD's other 197 tracks (hair/accessory/weapon bones) are ignored by
     * {@link VmdSampler} — the dance still drives the full body rig.
     *
     * <p>Translation scale is the standard MMD 1/12 (same as the model scale). kiss-me-more's foot-IK
     * translations (8…24 units) are larger than gentle dances (铃: 0.5…0.8) because it is a dynamic
     * stage dance with real steps; the leg IK then bends the knee (7…15°) instead of standing stiff.
     * Squeezing the translations (1/240) made the legs rigid — the knee barely moved.
     */
    private static void testSilverWolf() {
        ModelInstance instance = createMmdInstance("silver_wolf.mmd.zip", "银狼.pmx", "test_silver_wolf");
        if (instance == null) return;
        // 银狼是游戏转换模型：322 个刚体/501 个约束（大量 Hair/Belt/QZ/QH/YD 动态布料刚体）。
        // 动态刚体的碰撞/约束力会通过链条扰动腿部骨骼，覆盖 IK 解算结果 → 大腿狂转、脚翻起。
        // 默认启用物理（-Dkasuga.testModelPhysics=false 关闭对比；物理会驱动 QZ/QH/YD 飘带布料摆动）。
        attachMmdPhysics(instance, "银狼.pmx");
        silverWolfPlayer = playVmdClip(instance, silverWolfPlayer, SILVER_WOLF_VMD,
                new VmdSampler(new Vector3f(1f / 12f)), "kiss-me-more");
    }

    private static ModelInstance createMmdInstance(String fileName, String modelName, String instanceName) {
        ResourceLocation modelLoc = PipelineRegistry.pmxLoader().getLocByFileAndName(
                ResourceLocation.tryBuild(KasugaLib.MODID, "models/pmx/" + fileName), modelName);
        if (modelLoc == null) {
            if (!missingMmdLogged) {
                missingMmdLogged = true;
                LOGGER.warn("Test MMD model '{}' is unavailable after resource reload; check contentTesting models/model_proxy.json",
                        modelName);
            }
            return null;
        }
        missingMmdLogged = false;
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.pmx();
        if (pipeline.hasInstance(modelLoc, instanceLoc)) {
            return pipeline.getInstance(modelLoc, instanceLoc);
        }
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, new Transform(), null, null);
        if (instance == null) return null;
        pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
        return instance;
    }

    /**
     * Loads a VMD clip from the resource manager and plays it as a looping bone/morph clip on the
     * instance through the new animation pipeline: {@code VmdReader} → {@link VmdMotion} →
     * {@link VmdSampler} (Bezier, translations × sampler scale to match the model) → {@link AnimationPlayer}
     * (clock + {@code PoseSink.applyPose}). Render frames flush the pose; {@link #tick} advances the clock.
     * Returns the running player (or the previous one when this instance already has it).
     */
    private static AnimationPlayer<VmdMotion> playVmdClip(ModelInstance instance,
                                                          AnimationPlayer<VmdMotion> existing,
                                                          ResourceLocation vmdLoc, VmdSampler sampler, String label) {
        if (existing != null && existing.model() == instance) return existing;
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(vmdLoc);
            if (resource.isEmpty()) {
                LOGGER.warn("VMD clip '{}' not found; check contentTesting assets", vmdLoc);
                return existing;
            }
            byte[] bytes;
            try (InputStream in = resource.get().open()) {
                bytes = in.readAllBytes();
            }
            VmdMotion motion = new VmdReader().read(ByteBuffer.wrap(bytes));
            AnimationPlayer<VmdMotion> player = new AnimationPlayer<>(instance);
            instance.setPoseDriver(player);
            player.play(sampler, motion, true);
            float duration = (float) motion.boneTracks().values().stream()
                    .flatMap(track -> track.stream())
                    .mapToLong(VmdMotion.BoneKeyframe::frame)
                    .max().orElse(0) / VmdSampler.FRAMES_PER_SECOND;
            LOGGER.info("Playing VMD '{}' on {} ({}): {} bone tracks, {} morph tracks, {:.1f}s loop",
                    vmdLoc, instance, label, motion.boneTracks().size(), motion.morphTracks().size(), duration);
            return player;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to load or play VMD clip '{}'", vmdLoc, exception);
            return existing;
        }
    }

    /**
     * The bunny ragdoll profile references rigid-body indices authored for that specific model
     * ({@code tda_bunny_miku.json}), so it only applies to the tda bunny miku. Every other MMD model
     * (e.g. 铃) enables its own authored PMX/PMD rigid bodies and joints instead.
     */
    private static void attachMmdPhysics(ModelInstance instance, String modelName) {
        if (instance == null || currentMmdInstance == instance) return;
        currentMmdInstance = instance;
        if (!Boolean.parseBoolean(System.getProperty("kasuga.testModelPhysics", "true"))) return;
        if (!isBunnyMiku(modelName)) {
            enableAuthoredPhysics(instance);
            return;
        }
        try {
            MinecraftRagdollConfig config = MinecraftRagdollConfig.load(
                    Minecraft.getInstance().getResourceManager(), TEST_MMD_PHYSICS);
            config.attach(instance, () -> Minecraft.getInstance().level,
                    Boolean.parseBoolean(System.getProperty("kasuga.testModelPhysicsDrop", "true")));
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to attach test ragdoll config {}", TEST_MMD_PHYSICS, exception);
        }
    }

    private static boolean isBunnyMiku(String modelName) {
        return modelName != null && modelName.toLowerCase(Locale.ROOT).contains("tda bunny miku");
    }

    private static void enableAuthoredPhysics(ModelInstance instance) {
        MmdRagdoll ragdoll = instance.enablePhysics();
        if (ragdoll == null) {
            LOGGER.warn("Authored PMX physics unavailable (Box3D missing or no rigid bodies)");
            return;
        }
        MinecraftRagdollRuntime.register(instance, MinecraftRagdollConfig.UpdateMode.RENDER_FRAME);
    }

    private static void testObj() {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "models/obj/df5_frame.obj");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "test_wheel");
        addStaticInstance(PipelineRegistry.obj(), modelLoc, instanceLoc, null);
    }

    private static void testBe() {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "models/be/test_model_complicate.geo.json");
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
