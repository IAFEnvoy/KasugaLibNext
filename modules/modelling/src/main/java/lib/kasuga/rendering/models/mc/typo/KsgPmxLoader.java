package lib.kasuga.rendering.models.mc.typo;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.KsgPmxContext;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.SpriteSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.List;

public class KsgPmxLoader extends PMXLoader<ZipHelper, ResourceLocation, ZipResource, KsgPmxContext> {

    private ZipHelper loadingFile;

    private ZipResource loadingModel;

    private final List<Pair<ZipResource, Texture>> loadedTextures;

    private final HashMap<ZipResource, Texture> loadedTextureMap;

    public final Vector3f modelScale = new Vector3f(1.0f / 12.0f);

    public final MCTexture MISSING, MISSING_TRANSPARENCY;

    private final Map<ResourceLocation, Map<String, ResourceLocation>> loadedModelMap;

    public KsgPmxLoader(String name) {
        super(name);
        loadingFile = null;
        loadingModel = null;
        loadedTextures = new ArrayList<>();
        loadedTextureMap = new HashMap<>();
        MISSING = new MCTexture("missingno",
                () -> new net.minecraft.client.resources.model.Material(
                        RenderState.KSG_LAYER_0, MissingTextureAtlasSprite.getLocation()
                ), 16, 16,
                new MCTextureData(
                        MissingTextureAtlasSprite.getLocation(),
                        Constants.TEXTURE_BASIC
                ));
        MISSING_TRANSPARENCY = new MCTexture("transparencyno",
                () -> new net.minecraft.client.resources.model.Material(
                        RenderState.KSG_LAYER_0, RenderState.DEFAULT_TRANSPARENCY
                ), 16, 16,
                new MCTextureData(
                        RenderState.DEFAULT_TRANSPARENCY,
                        Constants.TEXTURE_BASIC
                ));
        loadedModelMap = new HashMap<>();
    }

    @Override
    public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {
        boolean useInternalToon = material.usingInternalTexture;
        int index = material.textureIndex.intValue();
        final ResourceLocation rl = getDefaultTextureIdentifier(index, loadedTextures);
        ZipResource id = null;
        if (rl == null) {
            id = loadedTextures.get(index).getFirst();
            builder.useTexture(id);
        } else {
            builder.useTexture(rl);
        }
        final Object identifier = id != null ? id : rl;
        builder.addSpriteBuildingFunc((matb, sprb, mat) -> {
            SpriteSetBuilder spriteBuilder = (SpriteSetBuilder) sprb;
            spriteBuilder
                    .textureId(identifier)
                    .culled(!material.flags.noCull)
                    .shade(material.flags.drawShadow)
                    .color(new Vector4f(material.diffuseColor))
                    .endSprite();
        }).endMaterial(material);
    }

    public ResourceLocation getDefaultTextureIdentifier(int index, List<?> list) {
        if (index < 0) return RenderState.DEFAULT_TRANSPARENCY;
        if (index >= list.size()) return MissingTextureAtlasSprite.getLocation();
        Object pair = list.get(index);
        if (!(pair instanceof Pair<?,?> p)) return MissingTextureAtlasSprite.getLocation();
        return p.getFirst() == null ? MissingTextureAtlasSprite.getLocation() : null;
    }

    @Override
    public ZipResource getTextureIdentifier(String texturePath) {
        Objects.requireNonNull(loadingFile);
        return loadingFile.getResource(texturePath.toLowerCase(Locale.ROOT));
    }

    public Texture getTexture(ZipResource s) {
        try {
            ResourceLocation rl = ResourceLocation.tryBuild("kasuga_lib", "textures/pmx/" + s.name().toLowerCase(Locale.ROOT));
            if (rl == null) {
                rl = ResourceLocation.tryBuild("kasuga_lib", "textures/pmx/" + Integer.toUnsignedString(s.name().hashCode()));
            }
            if (loadedTextureMap.containsKey(s)) {
                Texture texture = loadedTextureMap.get(s);
                loadedTextures.add(Pair.of(s, texture));
                return texture;
            }
            ByteArrayInputStream bis = new ByteArrayInputStream(copyBytes(s.buffer()));
            BufferedImage image = ImageIO.read(bis);
            bis.close();
            if (image == null) {
                if (!s.name().toLowerCase(Locale.ROOT).endsWith(".tga")) {
                    throw new RuntimeException("Unsupported image format: " + s.name());
                }
                ByteBuffer bb = null;
                ByteBuffer copied = MemoryUtil.memAlloc(s.buffer().remaining());
                copied.order(ByteOrder.nativeOrder());
                copied.put(s.buffer().duplicate());
                copied.flip();

                IntBuffer w = MemoryUtil.memAllocInt(1);
                IntBuffer h = MemoryUtil.memAllocInt(1);
                IntBuffer comp = MemoryUtil.memAllocInt(1);

                try {
                    bb = STBImage.stbi_load_from_memory(copied, w, h, comp, 4);
                    if (bb == null) {
                        throw new RuntimeException("Failed to load TGA image: " + STBImage.stbi_failure_reason());
                    }
                    image = new BufferedImage(w.get(0), h.get(0), BufferedImage.TYPE_4BYTE_ABGR);
                    byte[] imageData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                    for (int i = 0; i < bb.remaining(); i += 4) {
                        // STBImage loads in RGBA, but BufferedImage expects ABGR, so we need to swap the R and B channels
                        byte r = bb.get(i);
                        byte g = bb.get(i + 1);
                        byte b = bb.get(i + 2);
                        byte a = bb.get(i + 3);

                        imageData[i] = a;
                        imageData[i + 1] = b;
                        imageData[i + 2] = g;
                        imageData[i + 3] = r;
                    }
                } finally {
                    MemoryUtil.memFree(copied);
                    MemoryUtil.memFree(w);
                    MemoryUtil.memFree(h);
                    MemoryUtil.memFree(comp);
                    if (bb != null) {
                        STBImage.stbi_image_free(bb);
                    }
                }
            }
            if (image.getType() != BufferedImage.TYPE_4BYTE_ABGR) {
                BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = converted.createGraphics();
                try {
                    g.setComposite(AlphaComposite.Src);
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
                image = converted;
            }
            CombinedTextureManager textureManager = Constants.TEXTURE_BASIC;
            int w = image.getWidth(), h = image.getHeight();
            net.minecraft.client.resources.model.Material mat = new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, rl);
            Pair<ResourceLocation, BufferedImage> pair = Pair.of(rl, image);
            MCTextureData data = new MCTextureData(pair, textureManager);
            MCTexture mcTexture = new MCTexture(s.name(), () -> mat, w, h, data);
            textureManager.load(pair);
            loadedTextures.add(Pair.of(s, mcTexture));
            loadedTextureMap.put(s, mcTexture);
            return mcTexture;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertex) {
        if (vertex.isEmpty() || first == null) {
            throw new IllegalArgumentException("Vertex collection cannot be empty");
        }
        Vector3f position = new Vector3f(first.position).mul(modelScale);
        return new Vertex(position, first);
    }

    @Override
    public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) {
        return new Mesh(new Vertex[]{v1, v2, v3}, new Vector3f(), new Transform(), new Material[1], mesh);
    }

    @Override
    public Bone getBone(List<PmxBone> bones, PmxBone bone) {
        bone.position.mul(modelScale);
        if (bone.tailObject instanceof Vector3f v) v.mul(modelScale);
        return new Bone(bone.localBoneName, super.calculateBoneTransform(bones, bone), bone);
    }

    @Override
    public @Nullable ModelData getModelData(PmxHeader header) {
        return header;
    }

    @Override
    public void beforeAllLoaders(ByteBuffer buffer, SerialContext<KsgPmxContext> context) {}

    @Override
    public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<KsgPmxContext> context) {}

    @Override
    public ByteBuffer getAsByteBuffer(ZipHelper input) {
        Objects.requireNonNull(loadingFile);
        Objects.requireNonNull(loadingModel);
        ByteBuffer buffer = loadingModel.buffer().duplicate();
        buffer.order(loadingModel.buffer().order());
        buffer.position(0);
        return buffer;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ZipHelper;
    }

    @Override
    public @Nullable <T> T getSource(String type, String sourceManagerName, String sourceName) {
        return super.getSource(type, sourceManagerName, sourceName);
    }

    @Override
    public Texture loadTexture(Object textureIdentifier) {
        if (isTextureLoading()) {
            if (textureIdentifier == null) {
                loadedTextures.add(
                        Pair.of(
                                null, MISSING
                        )
                );
                return null;
            }
            return this.getTexture((ZipResource) textureIdentifier);
        } else if (textureIdentifier == null) {
            return null;
        }
        return loadedTextureMap.get(textureIdentifier);
    }

    public ResourceLocation getLocation(ResourceLocation fileLoc, ZipResource resource) {
        String filePath = fileLoc.getPath();
        String convertNameAsDir = filePath.endsWith(".pmx.zip")
                ? filePath.substring(0, filePath.length() - ".pmx.zip".length()) + "/"
                : filePath + "/";
        String resourceName = resource.name().toLowerCase(Locale.ROOT);
        ResourceLocation loc = ResourceLocation.tryBuild(
                fileLoc.getNamespace(),
                convertNameAsDir + resourceName
        );
        if (loc == null) {
            loc = ResourceLocation.tryBuild(
                    fileLoc.getNamespace(),
                    convertNameAsDir + Integer.toUnsignedString(resourceName.hashCode()) + ".pmx"
            );
        }
        return loc;
    }

    public ResourceLocation getLocByFileAndName(ResourceLocation fileLoc, String name) {
        String lowerName;
        for (ResourceLocation loc : loadedModelMap.keySet()) {
            if (!loc.equals(fileLoc)) continue;
            Map<String, ResourceLocation> map = loadedModelMap.get(loc);
            lowerName = name.toLowerCase(Locale.ROOT);
            if (map.containsKey(lowerName)) {
                return map.get(lowerName);
            }
        }
        return null;
    }

    @Override
    public Map<ResourceLocation, Model> load(ResourceLocation s, ZipHelper input) {
        loadedTextures.clear();
        loadedTextureMap.clear();
        loadingFile = input;
        try {
            List<ZipResource> models = input.searchNameForResource(name -> name.endsWith(".pmx"));
            if (models.isEmpty()) return new HashMap<>();
            Map<ResourceLocation, Model> result = new HashMap<>();
            for (ZipResource model : models) {
                loadingModel = model;
                registerDefaultTextures();
                ResourceLocation rl = getLocation(s, model);
                result.putAll(super.load(rl, input));
                this.loadedModelMap.computeIfAbsent(s, k -> new HashMap<>()).put(model.name(), rl);
                loadedTextures.clear();
            }
            return result;
        } finally {
            loadingModel = null;
            loadingFile = null;
            loadedTextures.clear();
            loadedTextureMap.clear();
            materialSetBuilder().clear();
        }
    }

    private void registerDefaultTextures() {
        materialSetBuilder().registerTexture(RenderState.DEFAULT_TRANSPARENCY, MISSING_TRANSPARENCY);
        materialSetBuilder().registerTexture(MissingTextureAtlasSprite.getLocation(), MISSING);
    }

    private byte[] copyBytes(ByteBuffer source) {
        ByteBuffer duplicate = source.duplicate();
        duplicate.position(0);
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }
}
