package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.data_type.MCRenderableContext;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class MCBackend extends Backend<MCBridge, BackendInstance, MCBackendContext, MCBackend.BackendTransform> implements AutoCloseable {

    @Getter
    public final ExecutorService executor;

    private float t = 0;

    public MCBackend() {
        executor = newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @Override
    public void render(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable, MCBackendContext context) {
        PoseStack poseStack = context.getPoseStack();
        poseStack.pushPose();

        // Drive any attached pose driver (e.g. FSM) at frame rate before the GPU upload in drawBuffer. No-op for
        // static models (no driver) — the shared render path is unaffected.
        renderable.getModelInstance().sample(context.getPartialTickFraction());

        BackendTransform transform = renderable.beforeRender(context);
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
        instance.drawBuffer(poseStack.last(), RenderState.getRenderType(),
                context.getModelViewMatrix(), context.getProjectionMatrix(),
                emissive);

        poseStack.popPose();
    }

    private boolean isVisible(MCBackendContext context, @Nullable BackendTransform transform, KsgVertexBuffer buffer) {
        if (context.getFrustum() == null || !buffer.hasBounds()) {
            return true;
        }
        if (transform != null && (transform.getRotation() != null || transform.getScale() != null)) {
            return true;
        }
        Vector3f position = transform == null ? null : transform.getPosition();
        AABB bounds = buffer.getBounds(position);
        return context.getFrustum().isVisible(bounds);
    }

    @Override
    public void close() throws Exception {
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
