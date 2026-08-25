package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.data_type.MCRenderableContext;
import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class MCBackend extends Backend<MCBridge, BackendInstance, MCBackendContext, MCBackend.BackendTransform> implements AutoCloseable {

    @Getter
    public final ExecutorService executor;

    private float t = 0;
    private final GlobalModelBatcher globalBatcher = new GlobalModelBatcher();
    private final Set<ModelInstance> sampledThisFrame = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Static bounding radius per instance, computed on first frustum test. */
    private final Map<ModelInstance, Float> boundsCache = new IdentityHashMap<>();
    // Reused scratch for the per-frame visibility box (render thread only).
    private final Vector3f visibleBoundsScratchMin = new Vector3f();
    private final Vector3f visibleBoundsScratchMax = new Vector3f();

    public MCBackend() {
        executor = newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @Override
    public void render(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable, MCBackendContext context) {
        // Scheduling gate, mirroring vanilla's "renderer not called" semantics:
        // schedule mode → view distance → frustum. Culled instances neither
        // sample animation nor touch GPU buffers this frame.
        ModelInstance model = renderable.getModelInstance();
        BackendTransform transform = renderable.beforeRender(context);
        if (!passesSchedule(model, transform, context)) return;

        PoseStack poseStack = context.getPoseStack();
        poseStack.pushPose();

        // Animation is sampled once per rendered frame. Configured ragdoll
        // physics is advanced by MinecraftRagdollRuntime independently of
        // render visibility; tying it to this method froze culled instances.
        if (sampledThisFrame.add(model)) {
            model.sample(context.getPartialTickFraction());
        }

        LightData lightData;
        int overlay;
        float emissive;
        if (transform != null) {
            if (transform.isAppliesTransform()) {
                transform.applyTransform(poseStack);
            }
            lightData = transform.getLightAndBrightness(context.getLevel());
            overlay = transform.getOverlay();
            emissive = transform.emissiveStrength;
        } else {
            lightData = new LightData(0, 0, LightTexture.FULL_BLOCK, (float) Math.abs(Math.sin(t)));
            t += 0.05f;
            overlay = OverlayTexture.NO_OVERLAY;
            emissive = 1f;
        }

        BackendInstance instance = renderable.apply();
        instance.updateLightData(lightData.packedLight(), overlay, lightData.brightness());
        if (!globalBatcher.isCollecting()
                || !globalBatcher.submit(instance, poseStack.last().pose(), poseStack.last().normal(), emissive)) {
            instance.drawBuffer(poseStack.last(), RenderState.getRenderType(),
                    context.getModelViewMatrix(), context.getProjectionMatrix(), emissive);
        }

        poseStack.popPose();
    }

    /**
     * Per-frame visibility decision for one instance: scheduler mode first
     * (ALWAYS / MANUAL / VANILLA_RENDERER marks), then view distance, then
     * frustum. The bounds are taken from the LIVE evaluated skeleton — bone
     * absolutes include root, IK and physics writeback — because a ragdoll's
     * rendered geometry wanders far from its root transform; testing against
     * static bind-pose bounds culled on-screen ragdolls (they flickered
     * invisible whenever physics dragged the pose outside the authored box).
     */
    private boolean passesSchedule(ModelInstance model,
                                   @Nullable BackendTransform transform,
                                   MCBackendContext context) {
        if (!ModelRenderScheduler.shouldRender(model)) return false;

        Vector3f position = transform == null ? null : transform.getPosition();
        if (position == null && !hasEvaluatedBones(model)) return true;

        visibleBoundsScratchMin.set(Float.MAX_VALUE);
        visibleBoundsScratchMax.set(-Float.MAX_VALUE);
        boolean live = scanEvaluatedBounds(model,
                visibleBoundsScratchMin, visibleBoundsScratchMax);
        if (!live) {
            // Skeleton never evaluated yet: conservative static box at the root.
            if (position == null) return true;
            float radius = boundsRadius(model);
            visibleBoundsScratchMin.set(position.x - radius, position.y - radius, position.z - radius);
            visibleBoundsScratchMax.set(position.x + radius, position.y + radius, position.z + radius);
        }

        double centerX = (visibleBoundsScratchMin.x + visibleBoundsScratchMax.x) * 0.5;
        double centerY = (visibleBoundsScratchMin.y + visibleBoundsScratchMax.y) * 0.5;
        double centerZ = (visibleBoundsScratchMin.z + visibleBoundsScratchMax.z) * 0.5;

        Vec3 camera = context.getCamera() != null ? context.getCamera().getPosition() : null;
        if (camera != null && !ModelRenderScheduler.withinRenderDistance(model,
                (float) camera.distanceToSqr(centerX, centerY, centerZ))) {
            return false;
        }

        Frustum frustum = context.getFrustum();
        if (frustum == null) return true;
        float margin = boundsRadius(model);
        return frustum.isVisible(new AABB(
                visibleBoundsScratchMin.x - margin, visibleBoundsScratchMin.y - margin,
                visibleBoundsScratchMin.z - margin,
                visibleBoundsScratchMax.x + margin, visibleBoundsScratchMax.y + margin,
                visibleBoundsScratchMax.z + margin));
    }

    private static boolean hasEvaluatedBones(ModelInstance model) {
        return !model.getSkeletonInstance().getAbsoluteTransforms().isEmpty();
    }

    /**
     * Scans every evaluated bone's world position into {@code min}/{@code max}.
     * Returns false when nothing has been evaluated yet. Package-private so
     * the extent math stays unit-testable without Minecraft types.
     */
    static boolean scanEvaluatedBounds(ModelInstance model, Vector3f min, Vector3f max) {
        var absolute = model.getSkeletonInstance().getAbsoluteTransforms();
        if (absolute.isEmpty()) return false;
        for (lib.kasuga.rendering.models.uml.math.Transform transform : absolute.values()) {
            Matrix4f m = transform.transform();
            float x = m.m30(), y = m.m31(), z = m.m32();
            min.set(Math.min(min.x, x), Math.min(min.y, y), Math.min(min.z, z));
            max.set(Math.max(max.x, x), Math.max(max.y, y), Math.max(max.z, z));
        }
        return true;
    }

    /** Static bounding radius of the authored vertex cloud — used as growth margin. */
    private float boundsRadius(ModelInstance model) {
        return boundsCache.computeIfAbsent(model, ignored -> {
            Vector3f minimum = new Vector3f(Float.MAX_VALUE);
            Vector3f maximum = new Vector3f(-Float.MAX_VALUE);
            for (Vertex vertex : model.getModel().getVertices()) {
                Vector3f p = vertex.getPosition();
                minimum.min(p);
                maximum.max(p);
            }
            Vector3f half = maximum.sub(minimum, new Vector3f()).mul(0.5f).absolute();
            return half.length();
        });
    }

    @Override
    public void renderAllObjects(MCBackendContext context) {
        sampledThisFrame.clear();
        // Vanilla entities/block entities rendered earlier in this frame have
        // deposited their decisions; flip them in before evaluating schedules.
        ModelRenderScheduler.flipFrame();
        try {
            if (BackendInstance.isIrisEnabled() || RenderState.GLOBAL_BATCH_RENDER_TYPE == null) {
                super.renderAllObjects(context);
                return;
            }
            globalBatcher.begin(context.getModelViewMatrix(), context.getProjectionMatrix());
            try {
                super.renderAllObjects(context);
            } finally {
                globalBatcher.flush();
            }
        } finally {
            sampledThisFrame.clear();
        }
    }

    @Override
    public boolean remove(Object key) {
        boolean removed = super.remove(key);
        if (removed && key instanceof ModelInstance model) {
            boundsCache.remove(model);
            ModelRenderScheduler.detach(model);
        }
        return removed;
    }

    @Override
    public void close() throws Exception {
        globalBatcher.close();
        boundsCache.clear();
        executor.shutdown();
    }

    public record LightData(int blockLight, int skyLight, int packedLight, float brightness) {

        @Override
        public int hashCode() {
            return Objects.hash(blockLight, skyLight, packedLight, brightness);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            LightData other = (LightData) obj;
            return blockLight == other.blockLight &&
                    skyLight == other.skyLight &&
                    packedLight == other.packedLight &&
                    Float.compare(other.brightness, brightness) == 0;
        }
    }

    @Getter
    public static class BackendTransform {

        @Nullable
        private final Vector3f position, rotation, scale;
        private final boolean isHurt, isGlowing, enableWorldLightAndBrightness, enableAutoOverlay;
        private final float emissiveStrength, brightness;
        private final int directlyGivenPackedLight, directlyGivenPackedOverlay;
        /** When false the TRS still feeds lighting/culling but is NOT pushed into the pose stack. */
        private final boolean appliesTransform;

        public BackendTransform(Vector3f position, Vector3f rotation, Vector3f scale,
                                boolean isHurt, boolean isGlowing, boolean enableWorldLightAndBrightness,
                                boolean enableAutoOverlay,
                                float emissiveStrength, float brightness,
                                int directlyGivenPackedLight, int directlyGivenPackedOverlay) {
            this(position, rotation, scale, isHurt, isGlowing, enableWorldLightAndBrightness,
                    enableAutoOverlay, emissiveStrength, brightness,
                    directlyGivenPackedLight, directlyGivenPackedOverlay, true);
        }

        public BackendTransform(Vector3f position, Vector3f rotation, Vector3f scale,
                                boolean isHurt, boolean isGlowing, boolean enableWorldLightAndBrightness,
                                boolean enableAutoOverlay,
                                float emissiveStrength, float brightness,
                                int directlyGivenPackedLight, int directlyGivenPackedOverlay,
                                boolean appliesTransform) {
            this.position = position;
            this.rotation = rotation;
            this.scale = scale;
            this.isHurt = isHurt;
            this.isGlowing = isGlowing;
            this.enableWorldLightAndBrightness = enableWorldLightAndBrightness;
            this.emissiveStrength = emissiveStrength;
            this.directlyGivenPackedLight = directlyGivenPackedLight;
            this.enableAutoOverlay = enableAutoOverlay;
            this.directlyGivenPackedOverlay = directlyGivenPackedOverlay;
            this.brightness = brightness;
            this.appliesTransform = appliesTransform;
        }

        public void applyTransform(PoseStack poseStack) {
            if (position != null) {
                poseStack.translate(position.x(), position.y(), position.z());
            }
            if (rotation != null) {
                poseStack.mulPose(QuaternionHelper.fromXYZDegrees(rotation));
            }
            if (scale != null) {
                poseStack.scale(scale.x(), scale.y(), scale.z());
            }
        }

        public int getOverlay() {
            if (enableAutoOverlay) {
                return isHurt ? OverlayTexture.pack(0, true) :
                        (isGlowing ? OverlayTexture.pack(0, OverlayTexture.WHITE_OVERLAY_V) : OverlayTexture.NO_OVERLAY);
            } else {
                return directlyGivenPackedOverlay;
            }
        }

        public LightData getLightAndBrightness(Level level) {
            if (!enableWorldLightAndBrightness) {
                return new LightData(
                        0, 0, directlyGivenPackedLight, brightness
                );
            }
            boolean isPositionGiven = position != null;
            BlockPos pos = new BlockPos(
                    isPositionGiven ? Math.round(position.x()) : 0,
                    isPositionGiven ? Math.round(position.y()) : 0,
                    isPositionGiven ? Math.round(position.z()) : 0
            );
            return getLightData(level, pos);
        }
    }

    public static LightData getLightData(Level level, BlockPos pos) {
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        int packedLight = LightTexture.pack(blockLight, skyLight);
        float brightness = LightTexture.getBrightness(level.dimensionType(), level.getMaxLocalRawBrightness(pos));
        return new LightData(blockLight, skyLight, packedLight, brightness);
    }
}
