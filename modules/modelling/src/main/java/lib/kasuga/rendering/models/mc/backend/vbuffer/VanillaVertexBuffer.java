package lib.kasuga.rendering.models.mc.backend.vbuffer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lib.kasuga.mixins.client.AccessorByteBufferBuilder;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lombok.Getter;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Objects;

public class VanillaVertexBuffer implements IVertexBuffer {

    @Getter
    private final FlatModelData modelData;

    @Nullable
    private VertexBuffer vertexBuffer;

    @Getter
    private final int vertexSize, vertexCount;

    @Getter
    private final VertexFormat format;

    @Getter
    private final VertexFormat.Mode meshMode;

    @Getter
    private final int maxMergeGap;

    public VanillaVertexBuffer(FlatModelData modelData, VertexFormat format, int maxMergeGap) {
        this.modelData = modelData;
        this.vertexBuffer = null;
        this.vertexSize =  format.getVertexSize();
        this.vertexCount = modelData.getVertexCount();
        this.meshMode = modelData.getMcMeshMode();
        this.format = format;
        this.maxMergeGap = maxMergeGap;
    }

    @Override
    public VertexBuffer getVertexBuffer() {
        if (vertexBuffer == null) {
            uploadGpuBuffer();
        }
        return vertexBuffer;
    }

    @Override
    public void uploadGpuBuffer() {
//        if (vertexBuffer != null) vertexBuffer.close();
        int size = vertexSize * vertexCount;
        try (ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder(size)) {
            long sourcePointer = MemoryUtil.memAddress(modelData.getBuffer());
            long pointer = byteBufferBuilder.reserve(size);
            MemoryUtil.memCopy(sourcePointer, pointer, size);
            ((AccessorByteBufferBuilder) byteBufferBuilder).setWriteOffset(size);
            ByteBufferBuilder.Result result = byteBufferBuilder.build();
            Objects.requireNonNull(result);
            MeshData meshData = new MeshData(result, new MeshData.DrawState(
                    format,
                    vertexCount,
                    meshMode.indexCount(vertexCount),
                    meshMode,
                    VertexFormat.IndexType.least(vertexCount)
            ));
            if (vertexBuffer == null) {
                vertexBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            vertexBuffer.bind();
            try {
                vertexBuffer.upload(meshData);
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    @Override
    public void updateGpuBuffer(@Nullable BitSet dirtyVertices, boolean forceUploadAll) {
        if (forceUploadAll || vertexBuffer == null || dirtyVertices == null) {
            uploadGpuBuffer();
            return;
        }
        int count = dirtyVertices.cardinality();
        if (count == 0) return;
        if (count * 4 >= vertexCount * 3) {
            uploadGpuBuffer();
            return;
        }
        RenderSystem.assertOnRenderThread();
        BufferUploader.reset();
        int previousBinding = GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        try {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, ((AccessorVertexBuffer) vertexBuffer).getVertexBufferId());
            int start = dirtyVertices.nextSetBit(0);
            while (start >= 0) {
                int end = dirtyVertices.nextClearBit(start);
                int next = dirtyVertices.nextSetBit(end);
                while (next >= 0 && next - end <= maxMergeGap) {
                    end = dirtyVertices.nextClearBit(next);
                    next = dirtyVertices.nextSetBit(end);
                }
                end = Math.min(end, vertexCount);
                int byteCount = (end - start) * vertexSize;

                ByteBuffer slice = MemoryUtil.memSlice(modelData.getBuffer(), start * vertexSize, byteCount);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) start * vertexSize, slice);
                start = next;
            }
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBinding);
        }
    }

    @Override
    public void draw(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        if (vertexBuffer == null) return;
        vertexBuffer.drawWithShader(modelViewMatrix, projectionMatrix, shader);
    }

    @Override
    public void close() throws Exception {
        if (vertexBuffer != null) {
            vertexBuffer.close();
        }
    }
}
