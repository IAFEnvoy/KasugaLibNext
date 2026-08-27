package lib.kasuga.mixins.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface AccessorBufferBuilder {

    @Accessor("buffer")
    ByteBufferBuilder getBuffer();

    @Accessor("vertexSize")
    int getVertexSize();

    @Accessor("vertices")
    int getVertices();

    @Accessor("vertices")
    void setVertices(int vertices);

    @Accessor("format")
    VertexFormat getVertexFormat();
}
