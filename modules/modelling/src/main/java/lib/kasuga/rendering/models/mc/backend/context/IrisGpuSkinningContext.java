package lib.kasuga.rendering.models.mc.backend.context;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class IrisGpuSkinningContext implements GLContext {

    private int previousProgram;
    private int previousArrayBuffer;
    private int previousVertexArray;
    private int previousActiveTexture;
    private int previousTextureBinding;
    private boolean previousRasterizedDiscard;
    private int previousTransformFeedbackBuffer;
    private VertexBuffer currentBuffer;

    @Getter
    private final Supplier<VertexBuffer> bufferSupplier;

    @Getter
    @Nullable
    private final Consumer<ShaderInstance> beforeShaderApply;

    @Getter
    private final RenderType renderType;

    @Getter
    private final int boneTransformTextureId;

    @Getter
    private final int gpuSkinningOutputBufferId;

    @Getter
    private final int overriddenPositionLocation;

    @Getter
    private final Supplier<Integer> sourceVaoIdSupplier;


    public IrisGpuSkinningContext(@NotNull RenderType renderType,
                                  @NotNull Supplier<VertexBuffer> bufferSupplier,
                                  @NotNull Supplier<Integer> sourceVaoId,
                                  @Nullable Consumer<ShaderInstance> beforeShaderApply,
                                  int boneTransformTextureId,
                                  int overriddenPositionLocation,
                                  int gpuSkinningOutputBufferId) {
        this.renderType = renderType;
        this.boneTransformTextureId = boneTransformTextureId;
        this.bufferSupplier = bufferSupplier;
        this.overriddenPositionLocation = overriddenPositionLocation;
        this.gpuSkinningOutputBufferId = gpuSkinningOutputBufferId;
        this.sourceVaoIdSupplier = sourceVaoId;
        this.beforeShaderApply = beforeShaderApply;
        currentBuffer = null;
    }

    @Override
    public void enter(ShaderInstance shader, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        previousTextureBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        previousRasterizedDiscard = GL11.glGetBoolean(GL30.GL_RASTERIZER_DISCARD);
        previousTransformFeedbackBuffer = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);

        if (boneTransformTextureId != 0) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE7);
            GL15.glBindTexture(GL31.GL_TEXTURE_BUFFER, boneTransformTextureId);
        }

        renderType.setupRenderState();
        BufferUploader.reset();

        VertexBuffer irisBuffer = bufferSupplier.get();
        Objects.requireNonNull(irisBuffer);
        currentBuffer = irisBuffer;
        irisBuffer.bind();

        setupShaderState(shader, beforeShaderApply, modelViewMatrix, projectionMatrix,
                Minecraft.getInstance().getWindow());

        if (overriddenPositionLocation >= 0 && gpuSkinningOutputBufferId != 0) {
            GL20.glEnableVertexAttribArray(overriddenPositionLocation);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, gpuSkinningOutputBufferId);
            GL20.glVertexAttribPointer(overriddenPositionLocation, 3, GL11.GL_FLOAT, false, 12, 0L);
        }
    }

    public void dispatchSkinning(int numVertices) {
        int sourceVao = sourceVaoIdSupplier.get();
        if (sourceVao == 0 || gpuSkinningOutputBufferId == 0) return;

        boolean previousRasterDiscard = GL11.glGetBoolean(GL30.GL_RASTERIZER_DISCARD);
        int previousFeedbackBuffer = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);

        GL30.glBindVertexArray(sourceVao);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, gpuSkinningOutputBufferId);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        GL30.glBeginTransformFeedback(GL11.GL_POINTS);
        GL11.glDrawArrays(GL11.GL_POINTS, 0, numVertices);
        GL30.glEndTransformFeedback();
        GL30.glBindVertexArray(0);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, previousFeedbackBuffer);

        if (previousRasterDiscard) {
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        } else {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        }
    }

    @Override
    public void exit(ShaderInstance shader) {
        try {
            if (overriddenPositionLocation >= 0) {
                GL20.glDisableVertexAttribArray(overriddenPositionLocation);
            }

            VertexBuffer irisBuffer = currentBuffer;
            if (currentBuffer == null) throw new IllegalStateException("Vertex Buffer is not binding!");
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, ((AccessorVertexBuffer) irisBuffer).getVertexBufferId());
            DefaultVertexFormat.NEW_ENTITY.setupBufferState();

            if (gpuSkinningOutputBufferId != 0) {
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
                GL30.glBindVertexArray(0);
            }

            VertexBuffer.unbind();
            BufferUploader.reset();
            renderType.clearRenderState();

            if (boneTransformTextureId != 0) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE7);
                GL15.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            }

            GL20.glUseProgram(previousProgram);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL30.glBindVertexArray(previousVertexArray);
            RenderSystem.activeTexture(previousActiveTexture);
            GL15.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTextureBinding);
            if (previousRasterizedDiscard) {
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            } else {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            }
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, previousTransformFeedbackBuffer);
        } finally {
            if (shader != null) shader.clear();
        }
    }

    @Override
    public boolean isGpuSkinning() {
        return true;
    }
}
