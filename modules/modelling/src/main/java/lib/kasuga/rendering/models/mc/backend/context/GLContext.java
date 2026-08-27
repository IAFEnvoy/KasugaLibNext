package lib.kasuga.rendering.models.mc.backend.context;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.function.Consumer;

public interface GLContext {

    void enter(ShaderInstance shader, Matrix4f modelViewMatrix, Matrix4f projectionMatrix);

    void exit(ShaderInstance shader);

    boolean isGpuSkinning();

    void dispatchSkinning(int numVertices);

    default void setupShaderState(@Nullable ShaderInstance shader, @Nullable Consumer<ShaderInstance> beforeShaderApply, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Window window) {
        if (shader != null) {
            if (beforeShaderApply != null) {
                beforeShaderApply.accept(shader);
            }
            shader.setDefaultUniforms(
                    VertexFormat.Mode.QUADS,
                    modelViewMatrix,
                    projectionMatrix,
                    window
            );
            shader.apply();
        }
    }

}
