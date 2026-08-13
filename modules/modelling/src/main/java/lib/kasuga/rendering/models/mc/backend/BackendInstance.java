package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lib.kasuga.rendering.models.mc.backend.context.CpuSkinningContext;
import lib.kasuga.rendering.models.mc.backend.context.GLContext;
import lib.kasuga.rendering.models.mc.backend.context.IrisGpuSkinningContext;
import lib.kasuga.rendering.models.mc.backend.context.VanillaGpuSkinningContext;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.transform.BoneTransformTBO;
import lib.kasuga.rendering.models.mc.backend.transform.TransformFeedbackProgram;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IrisVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.vbuffer.VanillaVertexBuffer;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class BackendInstance {

    public static final VertexFormat VANILLA_FORMAT = RenderState.UML_VERTEX_FORMAT;
    public static final VertexFormat IRIS_FORMAT = DefaultVertexFormat.NEW_ENTITY;

    public static final ResourceLocation SKINNING_PROGRAM_LOCATION =
            ResourceLocation.parse("kasuga_lib:shaders/ksg_skinning.transform.glsl");

    private final ExecutorService executor;

    @Getter
    private final ModelInstance model;

    @Getter
    private final FlatModelData data;

    @Getter
    private final VertexFormat.Mode meshMode;

    @Nullable
    private final CpuSkinningContext cpuContext;

    @Nullable
    private final IrisGpuSkinningContext irisContext;

    @Nullable
    private final VanillaGpuSkinningContext vanillaContext;

    @Nullable
    private final IrisVertexBuffer irisBuffer;

    private final VanillaVertexBuffer vanillaBuffer;

    @Nullable
    private final TransformFeedbackProgram program;

    @Nullable
    private final BoneTransformTBO tbo;

    private final boolean cpuSkinning;

    private final Matrix4f matrixCache;

    private IVertexBuffer currentBuffer = null;

    public BackendInstance(ModelInstance instance,
                           ExecutorService executor,
                           boolean cpuSkinning) {
        this.model = instance;
        this.cpuSkinning = cpuSkinning;
        this.executor = executor;
        this.matrixCache = new Matrix4f();
        Map<VertexFormatElement, Integer> bufOffsets = FlatModelData.genVertexFormat(RenderState.UML_VERTEX_FORMAT);
        data = new FlatModelData(instance,
                RenderState.UML_VERTEX_FORMAT.getVertexSize(),
                bufOffsets, null,
                1.0f, true, cpuSkinning,
                OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT);
        this.meshMode = data.getMcMeshMode();
        if (cpuSkinning) {
            irisContext = null;
            vanillaContext = null;
            tbo = null;
            program = null;
            irisBuffer =
                    isIrisInstalled() ? new IrisVertexBuffer(data, getFormat(true),
                            10000, 64, this.executor) :
                    null;
            cpuContext = new CpuSkinningContext(
                    () -> getBuffer().getVertexBuffer(), null);
        } else {
            tbo = new BoneTransformTBO(instance.getSkeletonInstance());
            cpuContext = null;
            if (isIrisInstalled()) {
                program = new TransformFeedbackProgram(SKINNING_PROGRAM_LOCATION,
                        data::getBuffer, bufOffsets,
                        data.vertexSize);
                irisContext = new IrisGpuSkinningContext(getFormat(true),
                        () -> getBuffer().getVertexBuffer(), null,
                        tbo, program);
                irisBuffer = new IrisVertexBuffer(data, getFormat(true),
                        10000, 64, this.executor);
            } else {
                program = null;
                irisContext = null;
                irisBuffer = null;
            }
            vanillaContext = new VanillaGpuSkinningContext(tbo,
                    () -> getBuffer().getVertexBuffer(), null);
        }
        vanillaBuffer = new VanillaVertexBuffer(data, getFormat(false), 64);
    }

    public GLContext getContext() {
        if (cpuSkinning) return cpuContext;
        return isIrisEnabled() ? irisContext : vanillaContext;
    }

    public IVertexBuffer getBuffer() {
        if (currentBuffer == null) {
            return isIrisEnabled() ? irisBuffer : vanillaBuffer;
        }
        return currentBuffer;
    }

    public void updateLightData(int light, int overlay, float brightness) {
        data.setLight(light);
        data.setOverlay(overlay);
        data.setBrightness(brightness);
    }

    boolean prepareForGlobalBatch() {
        boolean updated = data.updateModel();
        if (!cpuSkinning && tbo != null && updated) {
            tbo.updateForVersion();
        }
        return true;
    }

    boolean usesCpuSkinning() {
        return cpuSkinning;
    }

    @Nullable
    BoneTransformTBO getBoneTransformTBO() {
        return tbo;
    }

    void clearBatchDirtyVertices() {
        data.getDirtyVertices().clear();
    }

    protected void drawBuffer(PoseStack.Pose pose, RenderType renderType,
                              Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                              float emissiveStrength) {
        GLContext context = getContext();
        IVertexBuffer buffer = getBuffer();
        currentBuffer = buffer;
        ShaderInstance shader = null;
        if (context == null || buffer == null) return;
        try {
            boolean updated = data.updateModel();
            if (!cpuSkinning && tbo != null && updated) {
                tbo.updateForVersion();
            }
            buffer.updateGpuBuffer(data.getDirtyVertices(), false);
            data.getDirtyVertices().clear();
            context.dispatchSkinning(data.vertexCount);
            if (isIrisEnabled()) {
                modelViewMatrix = matrixCache.set(modelViewMatrix).mul(pose.pose());
            }
            shader = context.enter(renderType, meshMode,
                    modelViewMatrix, projectionMatrix,
                    s -> setupShader(s, pose, emissiveStrength)
            ).get();
            buffer.draw(modelViewMatrix, projectionMatrix, shader);
        } finally {
            context.exit(shader, renderType);
            currentBuffer = null;
        }
    }

    public static boolean isIrisInstalled() {
        return IrisCompat.isIrisPresent();
    }

    public static boolean isIrisEnabled() {
        return IrisCompat.isUsingShaderPack();
    }

    public static VertexFormat getFormat() {
        return getFormat(isIrisEnabled());
    }

    public static VertexFormat getFormat(boolean iris) {
        if (iris) {
            return IrisCompat.getIrisFormat(IRIS_FORMAT, iris);
        } else {
            return VANILLA_FORMAT;
        }
    }

    public void setupShader(ShaderInstance s, PoseStack.Pose pose, float emissiveStrength) {
        if (!(s instanceof KasugaShaderInstance shader)) return;
        shader.setCurrentPose(pose);
        shader.setEmissiveStrength(emissiveStrength);
        shader.setLightData(data.getBrightness(), data.getLightmap(), data.getOverlay());
        shader.setGpuSkinningState(!cpuSkinning, tbo != null ? tbo.getTextureId() : 0);
    }
}
