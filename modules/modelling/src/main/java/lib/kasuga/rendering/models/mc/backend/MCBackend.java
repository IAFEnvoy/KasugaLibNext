package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.data_type.MCRenderableContext;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class MCBackend extends Backend<MCBridge, KsgVertexBuffer, MCBackendContext, MCBackend.BackendTransform> implements AutoCloseable {

    public final ExecutorService executor;

    public MCBackend() {
        executor = newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @Override
    public void render(BackendContext<MCBridge, KsgVertexBuffer, MCBackendContext, BackendTransform> renderable, MCBackendContext context) {
        PoseStack poseStack = context.getPoseStack();
        poseStack.pushPose();

        KasugaShaderInstance shader = null;
        boolean irisShaderPack = IrisCompat.isUsingShaderPack();
        if (!irisShaderPack) {
            RenderSystem.setShader(() -> RenderState.UML_SHADER_INSTANCE);
            shader  = (KasugaShaderInstance) RenderSystem.getShader();
        }

        BackendTransform transform = renderable.beforeRender(context);
        LightData lightData;
        int overlay;
        float emissive;
        if (transform != null) {
            transform.applyTransform(poseStack);
            lightData = transform.getLightAndBrightness(context.getLevel());
            overlay = transform.getOverlay();
            emissive = transform.emissiveStrength;
        } else {
            lightData = new LightData(0, 0, LightTexture.FULL_BLOCK, 1f);
            overlay = OverlayTexture.NO_OVERLAY;
            emissive = 1f;
        }

        KsgVertexBuffer buffer = renderable.apply();
        if (!isVisible(context, renderable, transform, buffer)) {
            poseStack.popPose();
            return;
        }
        RenderType renderType = RenderState.getRenderType();
        if (irisShaderPack) {
            BufferBuilder builder = (BufferBuilder) context.getVertexConsumer();
            buffer.drawStaticOnIrisPresent(builder, renderType, poseStack.last(), context.getModelViewMatrix(), context.getProjectionMatrix(),
                    lightData.brightness, lightData.packedLight, overlay, true);
        } else {
            buffer.drawStatic(renderType, poseStack.last(), context.getModelViewMatrix(), context.getProjectionMatrix(),
                    shader, lightData.brightness, emissive, lightData.packedLight, overlay, true);
        }

        poseStack.popPose();
    }

    private boolean isVisible(MCBackendContext context,
                              BackendContext<MCBridge, KsgVertexBuffer, MCBackendContext, BackendTransform> renderable,
                              @Nullable BackendTransform transform, KsgVertexBuffer buffer) {
        if (context.getFrustum() == null || !buffer.hasBounds()) {
            return true;
        }
        Transform rootTransform = renderable.getModelInstance().getSkeletonInstance().getTransform();
        return context.getFrustum().isVisible(transformBounds(buffer.getBounds(null), rootTransform, transform));
    }

    static AABB transformBounds(AABB localBounds, Transform rootTransform, @Nullable BackendTransform backendTransform) {
        Matrix4f transform = new Matrix4f(rootTransform.transform());
        if (backendTransform != null) {
            Vector3f position = backendTransform.getPosition();
            if (position != null) transform.translate(position);
            Vector3f rotation = backendTransform.getRotation();
            if (rotation != null) transform.rotate(QuaternionHelper.fromXYZDegrees(rotation));
            Vector3f scale = backendTransform.getScale();
            if (scale != null) transform.scale(scale);
        }

        Vector3f transformed = new Vector3f();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{localBounds.minX, localBounds.maxX}) {
            for (double y : new double[]{localBounds.minY, localBounds.maxY}) {
                for (double z : new double[]{localBounds.minZ, localBounds.maxZ}) {
                    transform.transformPosition((float) x, (float) y, (float) z, transformed);
                    minX = Math.min(minX, transformed.x);
                    minY = Math.min(minY, transformed.y);
                    minZ = Math.min(minZ, transformed.z);
                    maxX = Math.max(maxX, transformed.x);
                    maxY = Math.max(maxY, transformed.y);
                    maxZ = Math.max(maxZ, transformed.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
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

        public BackendTransform(Vector3f position, Vector3f rotation, Vector3f scale,
                                boolean isHurt, boolean isGlowing, boolean enableWorldLightAndBrightness,
                                boolean enableAutoOverlay,
                                float emissiveStrength, float brightness,
                                int directlyGivenPackedLight, int directlyGivenPackedOverlay) {
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
