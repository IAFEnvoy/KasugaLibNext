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
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class IrisVertexBuffer implements IVertexBuffer {

    @Getter
    private final FlatModelData modelData;

    @Getter
    private final VertexFormat format;

    @Getter
    private final VertexFormat.Mode meshMode;

    @Nullable
    private ByteBufferBuilder[] multiBufferBuilders;

    @Nullable
    private CompletableFuture[] futures;

    private final int multiThreadedThreshold, maxMergeGap;

    @Getter
    private final int vertexCount, vertexSize;

    @Getter
    private final ExecutorService executor;

    @Nullable
    private VertexBuffer vertexBuffer;

    private boolean valid;

    public IrisVertexBuffer(FlatModelData modelData, VertexFormat format,
                            int multiThreadedThreshold, int maxMergeGap,
                            ExecutorService executor) {
        this.modelData = modelData;
        this.vertexCount = modelData.getVertexCount();
        this.format = format;
        this.vertexSize = format.getVertexSize();
        this.meshMode = modelData.getMcMeshMode();
        this.maxMergeGap = maxMergeGap;
        this.executor = executor;
        this.multiThreadedThreshold = multiThreadedThreshold;
        this.multiBufferBuilders = null;
        this.futures = null;
        this.vertexBuffer = null;
        this.valid = false;
    }

    public void uploadGpuBuffer() {
        ByteBufferBuilder byteBufferBuilder = null;
        try {
            if (vertexCount < multiThreadedThreshold) {
                byteBufferBuilder = fillGpuCache(null, 0, modelData.getVertexCount());
            } else {
                int taskCount = Math.ceilDiv(vertexCount, multiThreadedThreshold);
                if (multiBufferBuilders == null || multiBufferBuilders.length != taskCount) {
                    if (multiBufferBuilders != null) {
                        for (ByteBufferBuilder bbb : multiBufferBuilders) {
                            bbb.close();
                        }
                    }
                    multiBufferBuilders = new ByteBufferBuilder[taskCount];
                }
                for (int i = 0; i < taskCount; i++) {
                    if (i == taskCount - 1) {
                        multiBufferBuilders[i] = new ByteBufferBuilder((vertexCount - i * multiThreadedThreshold) * vertexSize);
                    } else {
                        multiBufferBuilders[i] = new ByteBufferBuilder(multiThreadedThreshold * vertexSize);
                    }
                }
                if (futures == null || futures.length != taskCount) {
                    futures = new CompletableFuture[taskCount];
                }
                for (int i = 0; i < taskCount; i++) {
                    final int index = i;
                    final int taskStart = i * multiThreadedThreshold;
                    final int taskEnd = Math.min(taskStart + multiThreadedThreshold, vertexCount);
                    futures[i] = (CompletableFuture.runAsync(() -> {
                        fillGpuCache(multiBufferBuilders[index], taskStart, taskEnd - taskStart);
                    }, executor));
                }
                byteBufferBuilder = new ByteBufferBuilder(vertexCount * vertexSize);
                long pointer = ((AccessorByteBufferBuilder) byteBufferBuilder).getPointer();
                CompletableFuture.allOf(futures).join();

                for (int i = 0; i < taskCount; i++) {
                    ByteBufferBuilder bbb = multiBufferBuilders[i];
                    int taskStart = i * multiThreadedThreshold;
                    int taskVertices = Math.min(multiThreadedThreshold, vertexCount - taskStart);
                    int byteCount = taskVertices * vertexSize;
                    long p = ((AccessorByteBufferBuilder) bbb).getPointer();
                    MemoryUtil.memCopy(p, pointer, byteCount);
                    MemoryUtil.nmemFree(p);
                    pointer += byteCount;
                    multiBufferBuilders[i] = null;
                }

                ((AccessorByteBufferBuilder) byteBufferBuilder).setWriteOffset(vertexCount * vertexSize);
            }
            ByteBufferBuilder.Result result = Objects.requireNonNull(byteBufferBuilder.build());
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
        } finally {
            if (byteBufferBuilder != null) {
                byteBufferBuilder.close();
            }
        }
        valid = true;
    }

    @Override
    public void draw(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        if (vertexBuffer == null) return;
        vertexBuffer.draw();
    }

    @Override
    public void updateGpuBuffer(@Nullable BitSet dirtyVertices, boolean forceUploadAll) {
        if (forceUploadAll || vertexBuffer == null || dirtyVertices == null) {
            uploadGpuBuffer();
            return;
        }
        int count = dirtyVertices.cardinality();
        if (count * 4 >= vertexCount * 3) {
            uploadGpuBuffer();
            return;
        }
        RenderSystem.assertOnRenderThread();
        BufferUploader.reset();
        int prevBinding = GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

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
                ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder((end - start) * vertexSize);
                ByteBufferBuilder bbb = fillGpuCache(byteBufferBuilder, start, end - start);
                ByteBufferBuilder.Result result = bbb.build();
                Objects.requireNonNull(result);
                ByteBuffer bb = result.byteBuffer();
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) start * vertexSize, bb);
                result.close();
                start = next;
            }
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBinding);
        }
    }

    protected ByteBufferBuilder fillGpuCache(ByteBufferBuilder byteBufferBuilder, int startIndex, int numVertices) {
        ByteBufferBuilder bbb;
        if (byteBufferBuilder == null) {
            int vSize = format.getVertexSize();
            bbb = new ByteBufferBuilder(numVertices * vSize);
        } else {
            bbb = byteBufferBuilder;
            ((AccessorByteBufferBuilder) bbb).setWriteOffset(0);
        }
        BufferBuilder bufferBuilder = new BufferBuilder(bbb, meshMode, format);

        int bufOffset, vOffset, color;
        ByteBuffer src = modelData.getBuffer();
        float x, y, z, nx, ny, nz, u, v;
        int srcVertexSize = modelData.getVertexSize();
        for (int i = startIndex; i < startIndex + numVertices; i++) {
            vOffset = i * srcVertexSize;

            bufOffset = vOffset + modelData.getColorOffset();
            color = src.getInt(bufOffset);
            if (!FlatModelData.IS_LITTLE_ENDIAN) {
                color = Integer.reverseBytes(color);
            }
            color = (color & 0xFF00FF00) | ((color & 0x00FF0000) >> 16) | ((color & 0x000000FF) << 16);
            bufOffset = vOffset + modelData.getPosOffset();
            x = src.getFloat(bufOffset);
            y = src.getFloat(bufOffset + 4);
            z = src.getFloat(bufOffset + 8);

            bufOffset = vOffset + modelData.getNormOffset();
            nx = ((float) src.get(bufOffset)) / 127f;
            ny = ((float) src.get(bufOffset + 1)) / 127f;
            nz = ((float) src.get(bufOffset + 2)) / 127f;

            bufOffset = vOffset + modelData.getUv0Offset();
            u = src.getFloat(bufOffset);
            v = src.getFloat(bufOffset + 4);

            bufferBuilder.addVertex(x, y, z, color, u, v,
                    modelData.getOverlay(), modelData.getLightmap(),
                    nx, ny, nz);
        }
        return bbb;
    }

    @Override
    public VertexBuffer getVertexBuffer() {
        if (vertexBuffer == null) {
            uploadGpuBuffer();
        }
        return vertexBuffer;
    }

    @Override
    public void close() throws Exception {
        if (vertexBuffer != null) {
            vertexBuffer.close();
        }
    }
}
