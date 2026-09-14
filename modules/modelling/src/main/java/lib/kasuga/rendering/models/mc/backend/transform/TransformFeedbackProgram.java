package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.*;

import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Getter
public class TransformFeedbackProgram implements AutoCloseable {

    public static final int STRIDE = 12;

    protected int programId = 0;
    protected boolean closed = false;
    protected final ResourceLocation programLocation;

    protected final Supplier<ByteBuffer> byteBufferSupplier;

    protected final Map<VertexFormatElement, Integer> bufOffsets;

    protected final int vertexSize;

    @Getter
    private int sourceBufferId = 0,
            sourceVaoId = 0,
            outputBufferId = 0;

    private boolean sourceValid = false;

    public TransformFeedbackProgram(ResourceLocation programLocation, Supplier<ByteBuffer> bufSupplier,
                                    Map<VertexFormatElement, Integer> bufOffsets, int vertexSize) {
        this.programLocation = programLocation;
        this.programId = createProgram();
        this.byteBufferSupplier = bufSupplier;
        this.bufOffsets = bufOffsets;
        this.vertexSize = vertexSize;
    }

    public void bind(int textureUnit, int textureId) {
        if (programId == 0) {return;}
        GL20.glUseProgram(programId);
        int samplerLocation = GL20.glGetUniformLocation(programId, "ksg_BoneTransforms");
        if (samplerLocation >= 0) {
            GL20.glUniform1i(samplerLocation, textureUnit - GL13.GL_TEXTURE0);
        }
        if (textureId != 0) {
            RenderSystem.activeTexture(textureUnit);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
        }
    }

    public void unbind(int previousProgram) {
        GL20.glUseProgram(previousProgram);
    }

    public boolean isValid() {
        return !closed && programId != 0;
    }


    @Override
    public void close() throws Exception {
        if (closed) {return;}
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        if (sourceBufferId != 0) {
            GL15.glDeleteBuffers(sourceBufferId);
            sourceBufferId = 0;
        }
        if (sourceVaoId != 0) {
            GL30.glDeleteVertexArrays(sourceVaoId);
            sourceVaoId = 0;
        }
        if (outputBufferId != 0) {
            GL15.glDeleteBuffers(outputBufferId);
            outputBufferId = 0;
        }
        sourceValid = false;
        closed = true;
    }

    protected int createProgram() {
        String shaderSource = loadShaderSource();
        if (shaderSource.isBlank()) {
            throw new IllegalStateException("Failed to load Transform Feedback " +
                    "shader source from " + programLocation);
        }
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, shaderSource);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);

        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 5, "Normal");
        GL20.glBindAttribLocation(program, 7, "Tangent");
        GL20.glBindAttribLocation(program, 8, "BoneBindingType");
        GL20.glBindAttribLocation(program, 9, "BoneIndices");
        GL20.glBindAttribLocation(program, 10, "BoneWeights");
        GL20.glBindAttribLocation(program, 11, "sdefR0");
        GL20.glBindAttribLocation(program, 12, "sdefR1");
        GL20.glBindAttribLocation(program, 13, "sdefC");
        GL30.glTransformFeedbackVaryings(program, new CharSequence[]{"tf_Position"}, GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(program);

        int linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS);
        GL20.glDetachShader(program, vertexShader);
        GL20.glDeleteShader(vertexShader);

        if (linked == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link Iris GPU skinning program: " + log);
        }

        return program;
    }

    protected String loadShaderSource() {
        Optional<Resource> resourceOpt = Minecraft.getInstance()
                .getResourceManager()
                .getResource(programLocation);
        if (resourceOpt.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[1024];
        try (Reader reader = resourceOpt.get().openAsReader()) {
            int len;
            while ((len = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, len);
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    protected static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        int compiled = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        if (compiled == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile shader: " + log);
        }
        return shader;
    }

    public void ensureSkinningObjects(int numVertices) {
        if (sourceBufferId == 0) {
            sourceBufferId = GL15.glGenBuffers();
        }
        if (sourceVaoId == 0) {
            sourceVaoId = GL30.glGenVertexArrays();
        }
        if (outputBufferId == 0) {
            outputBufferId = GL15.glGenBuffers();
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, outputBufferId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) numVertices * STRIDE, GL15.GL_DYNAMIC_DRAW);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    public void uploadSkinningSourceIfNeeded() {
        if (sourceValid) {
            return;
        }
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30.glBindVertexArray(sourceVaoId);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, sourceBufferId);
            ByteBuffer source = byteBufferSupplier.get().duplicate();
            source.clear();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, source, GL15.GL_STATIC_DRAW);
            setupIrisGpuSkinningSourceAttributes();
        } finally {
            GL30.glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        }
        sourceValid = true;
    }

    protected void setupIrisGpuSkinningSourceAttributes() {
        setupFloatAttribute(0, 3, bufOffsets.get(VertexFormatElement.POSITION));
        setupByteNormalAttribute(5, bufOffsets.get(VertexFormatElement.NORMAL));
        setupFloatAttribute(7, 4, bufOffsets.get(RenderState.TANGENT));
        setupIntAsFloatAttribute(8, 1, bufOffsets.get(RenderState.BONE_BINDING_TYPE));
        setupFloatAttribute(9, 4, bufOffsets.get(RenderState.BONE_INDICES));
        setupFloatAttribute(10, 4, bufOffsets.get(RenderState.BONE_WEIGHTS));
        setupFloatAttribute(11, 3, bufOffsets.get(RenderState.SDEF_R0));
        setupFloatAttribute(12, 3, bufOffsets.get(RenderState.SDEF_R1));
        setupFloatAttribute(13, 3, bufOffsets.get(RenderState.SDEF_C));
    }

    private void setupFloatAttribute(int index, int size, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, vertexSize, (long) offset);
    }

    private void setupByteNormalAttribute(int index, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, 3, GL11.GL_BYTE, true, vertexSize, (long) offset);
    }

    private void setupIntAsFloatAttribute(int index, int size, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_INT, false, vertexSize, (long) offset);
    }
}
