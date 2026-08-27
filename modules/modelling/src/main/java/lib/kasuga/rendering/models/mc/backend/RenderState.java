package lib.kasuga.rendering.models.mc.backend;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.rendering.BufferBuilderSupplier;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaTextureStateShard;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.util.Objects;
import java.util.function.Supplier;

public class RenderState {

    public static final VertexFormat UML_VERTEX_FORMAT;
    public static RenderType RENDER_TYPE;
    public static RenderType IRIS_COMPAT_RENDER_TYPE;
    public static final RenderStateShard.EmptyTextureStateShard UML_TEXTURE_STATE;
    public static final ResourceLocation DEFAULT_TRANSPARENCY = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/default_transparency.png");

    public static final VertexFormatElement TANGENT;
    public static final VertexFormatElement BONE_INDICES;
    public static final VertexFormatElement BONE_WEIGHTS;
    public static final VertexFormatElement BONE_BINDING_TYPE;

    public static final VertexFormatElement SDEF_R0, SDEF_R1, SDEF_C;

    public static final ResourceLocation KSG_LAYER_0 = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/layer_0.png");
    public static final ResourceLocation KSG_LAYER_1 = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/layer_1.png");
    public static final ResourceLocation KSG_LAYER_2 = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/layer_2.png");
    public static final ResourceLocation KSG_NORMAL_MAP = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/normals.png");
    public static final ResourceLocation KSG_METALLIC_MAP = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/metallic.png");
    public static final ResourceLocation KSG_EMISSIVE_MAP = ResourceLocation.tryBuild(KasugaLib.MODID, "textures/atlas/emissive.png");
    public static final ResourceLocation KSG_RENDER_TYPE = ResourceLocation.tryBuild(KasugaLib.MODID, "basic_render_type");
    public static final ResourceLocation KSG_IRIS_RENDER_TYPE = ResourceLocation.tryBuild(KasugaLib.MODID, "iris_render_type");

    public static RenderStateShard.ShaderStateShard UML_SHADER;
    public static ShaderInstance UML_SHADER_INSTANCE;
    private static final ResourceMetadata SPRITE_METADATA;



    static {
        Objects.requireNonNull(KSG_LAYER_0);
        Objects.requireNonNull(KSG_LAYER_1);
        Objects.requireNonNull(KSG_LAYER_2);
        Objects.requireNonNull(KSG_NORMAL_MAP);
        Objects.requireNonNull(KSG_METALLIC_MAP);
        Objects.requireNonNull(KSG_RENDER_TYPE);
        Objects.requireNonNull(KSG_IRIS_RENDER_TYPE);
        Objects.requireNonNull(KSG_EMISSIVE_MAP);
        Objects.requireNonNull(DEFAULT_TRANSPARENCY);

        SPRITE_METADATA = (new ResourceMetadata.Builder())
                .put(AnimationMetadataSection.SERIALIZER,
                        new AnimationMetadataSection(ImmutableList.of(new AnimationFrame(0, -1)), 16, 16, 1, false))
                .build();

        TANGENT = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT,
                VertexFormatElement.Usage.GENERIC,
                4
        );
        BONE_INDICES = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.INT,
                VertexFormatElement.Usage.GENERIC,
                4
        );
        BONE_WEIGHTS = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT,
                VertexFormatElement.Usage.GENERIC,
                4
        );
        BONE_BINDING_TYPE = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.INT,
                VertexFormatElement.Usage.GENERIC,
                1
        );
        SDEF_R0 = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT,
                VertexFormatElement.Usage.GENERIC,
                3
        );

        SDEF_R1 = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT,
                VertexFormatElement.Usage.GENERIC,
                3
        );

        SDEF_C = VertexFormatElement.register(
                VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT,
                VertexFormatElement.Usage.GENERIC,
                3
        );

        UML_VERTEX_FORMAT = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("Color", VertexFormatElement.COLOR)
                .add("UV0", VertexFormatElement.UV0)
                .add("UV1", VertexFormatElement.UV1)
                .add("UV2", VertexFormatElement.UV2)
                .add("Normal", VertexFormatElement.NORMAL)
                .add("Tangent", TANGENT)
                .add("BoneBindingType", BONE_BINDING_TYPE)
                .add("BoneIndices", BONE_INDICES)
                .add("BoneWeights", BONE_WEIGHTS)
                .add("sdefR0", SDEF_R0)
                .add("sdefR1", SDEF_R1)
                .add("sdefC", SDEF_C)
                .build();

        UML_TEXTURE_STATE = new KasugaTextureStateShard(() -> Constants.TEXTURE_BASIC);

        UML_SHADER =  new RenderStateShard.ShaderStateShard(() -> UML_SHADER_INSTANCE);
    }

    public static SpriteContents createDefaultSprite(ResourceLocation rl, Supplier<NativeImage> imageSup) {
        NativeImage image = imageSup.get();
        return new SpriteContents(rl, new FrameSize(image.getWidth(), image.getHeight()), image, SPRITE_METADATA);
    }

    public static NativeImage getNormalMapDefaultImage(int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setPixelRGBA(x, y, 0xFFFF7F7F);  // (127, 127, 255, 255) = 0xFFFF7F7F
            }
        }
        return image;
    }

    public static NativeImage getSpecularMapDefaultImage(int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setPixelRGBA(x, y, 0x00000A0A);  // (10, 10, 0, 0) = 0x00000A0A
            }
        }
        return image;
    }

    public static NativeImage getTransparencyDefaultImage(int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setPixelRGBA(x, y, 0x00000000);  // (0, 0, 0, 0) = 0x00000000
            }
        }
        return image;
    }

    public static SpriteContents createTransparencyDefaultSprite() {
        return createDefaultSprite(DEFAULT_TRANSPARENCY, () -> getTransparencyDefaultImage(16, 16));
    }

    public static RenderType getRenderType() {
        return IrisCompat.isUsingShaderPack() ? IRIS_COMPAT_RENDER_TYPE : RENDER_TYPE;
    }
}
