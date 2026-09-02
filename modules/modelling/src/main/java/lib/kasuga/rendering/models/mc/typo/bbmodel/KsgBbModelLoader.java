package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.google.gson.JsonParseException;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads Blockbench's native .bbmodel JSON format into the UML model structure. */
public final class KsgBbModelLoader implements ModelLoader<String, ResourceLocation, Integer> {
    private final String name;
    private final MaterialSetBuilder<Integer> materialSetBuilder;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources;

    public KsgBbModelLoader(String name) {
        this.name = name;
        this.materialSetBuilder = new MaterialSetBuilder<>(this);
        this.sidedSources = new HashMap<>();
    }

    @Override
    public Map<ResourceLocation, Model> load(ResourceLocation identifier, String input) {
        BbModelDefinition definition;
        try {
            definition = BbModelDefinition.parse(input);
        } catch (IllegalArgumentException | JsonParseException exception) {
            throw new IllegalArgumentException("Invalid Blockbench model " + identifier, exception);
        }

        Map<Integer, Material> materials = buildMaterials(identifier, definition);
        List<Vertex> vertices = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        Bone root = new Bone("root", new Transform(), null);

        if (definition.outliner().isEmpty()) {
            for (BbModelDefinition.Element element : definition.elements().values()) {
                appendElement(element, BlockBenchTransform.IDENTITY, true, definition, materials, root, vertices, meshes);
            }
        } else {
            for (BbModelDefinition.OutlineNode node : definition.outliner()) {
                appendOutline(node, BlockBenchTransform.IDENTITY, true, definition, materials, root, vertices, meshes);
            }
        }

        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new lib.kasuga.rendering.models.uml.structure.skeleton.Anchor[0], null, new Transform());
        Model model = new Model(
                vertices.toArray(new Vertex[0]), meshes.toArray(new Mesh[0]), new Bone[]{root}, skeleton,
                materialSetBuilder.endMaterialSet(), MeshMode.QUADS, null, null
        );
        return Map.of(identifier, model);
    }

    private Map<Integer, Material> buildMaterials(ResourceLocation identifier, BbModelDefinition definition) {
        Map<Integer, Material> result = new HashMap<>();
        for (BbModelDefinition.Texture texture : definition.textures()) {
            int textureReference = textureReference(texture);
            MCTexture modelTexture = createTexture(identifier, texture, definition);
            materialSetBuilder.registerTexture(textureReference, modelTexture)
                    .useTexture(textureReference)
                    .addSpriteBuildingFunc((builder, sprites, material) -> sprites.textureId(textureReference).endSprite())
                    .endMaterial(textureReference);
            result.put(textureReference, materialSetBuilder.getNamedMaterial(textureReference));
        }
        return result;
    }

    static int textureReference(BbModelDefinition.Texture texture) {
        // In native .bbmodel files, face.texture indexes the textures array.
        return texture.index();
    }

    private MCTexture createTexture(ResourceLocation modelIdentifier, BbModelDefinition.Texture texture,
                                    BbModelDefinition definition) {
        KasugaTextureManager textureManager = Constants.TEXTURE_BASIC;
        Object sourceIdentifier;
        ResourceLocation textureIdentifier;
        int width = positiveOr(texture.width(), definition.textureWidth());
        int height = positiveOr(texture.height(), definition.textureHeight());
        if (texture.source().startsWith("data:image/")) {
            BufferedImage image = decodeImage(texture.source(), modelIdentifier);
            width = image.getWidth();
            height = image.getHeight();
            textureIdentifier = embeddedTextureLocation(modelIdentifier, texture.index());
            sourceIdentifier = Pair.of(textureIdentifier, image);
        } else {
            textureIdentifier = resolveTextureLocation(modelIdentifier, texture.source(), texture.name());
            sourceIdentifier = textureIdentifier;
        }
        textureManager.load(sourceIdentifier);
        net.minecraft.client.resources.model.Material material =
                new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, textureIdentifier);
        return new MCTexture(texture.id(), () -> material, width, height,
                new MCTextureData(sourceIdentifier, textureManager, true));
    }

    private void appendOutline(BbModelDefinition.OutlineNode node, BlockBenchTransform parentTransform, boolean parentVisible,
                               BbModelDefinition definition, Map<Integer, Material> materials, Bone root,
                               List<Vertex> vertices, List<Mesh> meshes) {
        if (node instanceof BbModelDefinition.ElementNode elementNode) {
            BbModelDefinition.Element element = definition.elements().get(elementNode.elementId());
            if (element != null) {
                appendElement(element, parentTransform, parentVisible, definition, materials, root, vertices, meshes);
            }
            return;
        }
        BbModelDefinition.GroupNode group = (BbModelDefinition.GroupNode) node;
        BlockBenchTransform transform = parentTransform.child(group.origin(), group.rotation());
        for (BbModelDefinition.OutlineNode child : group.children()) {
            appendOutline(child, transform, parentVisible && group.visible(), definition, materials, root, vertices, meshes);
        }
    }

    private void appendElement(BbModelDefinition.Element element, BlockBenchTransform parentTransform, boolean parentVisible,
                               BbModelDefinition definition, Map<Integer, Material> materials, Bone root,
                               List<Vertex> vertices, List<Mesh> meshes) {
        if (!parentVisible || !element.visible()) return;
        BlockBenchTransform transform = parentTransform.child(element.origin(), element.rotation());
        if ("mesh".equals(element.type())) {
            appendMeshElement(element, transform, definition, materials, root, vertices, meshes);
        } else {
            appendCubeElement(element, transform, definition, materials, root, vertices, meshes);
        }
    }

    private void appendCubeElement(BbModelDefinition.Element element, BlockBenchTransform transform, BbModelDefinition definition,
                                   Map<Integer, Material> materials, Bone root, List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f from = new Vector3f(element.from()).sub(element.origin()).mul(1.0f / 16.0f);
        Vector3f to = new Vector3f(element.to()).sub(element.origin()).mul(1.0f / 16.0f);
        for (Map.Entry<lib.kasuga.rendering.models.mc.util.Direction, BbModelDefinition.Face> entry : element.cubeFaces().entrySet()) {
            Material material = materials.get(entry.getValue().texture());
            if (material == null) continue;
            Vector3f[] positions = cubeFacePositions(entry.getKey(), from, to);
            Vector2f[] uvs = rectangularUvs(entry.getValue().uv(), entry.getValue().rotation());
            appendFace(positions, uvs, transform, material, root, vertices, meshes);
        }
    }

    private void appendMeshElement(BbModelDefinition.Element element, BlockBenchTransform transform, BbModelDefinition definition,
                                   Map<Integer, Material> materials, Bone root, List<Vertex> vertices, List<Mesh> meshes) {
        for (BbModelDefinition.MeshFace face : element.meshFaces()) {
            Material material = materials.get(face.texture());
            if (material == null || face.vertices().size() < 3) continue;
            List<String> names = face.vertices();
            if (names.size() <= 4) {
                appendMeshFace(element, sortMeshFaceVertices(element.vertices(), names),
                        transform, definition, face, material, root, vertices, meshes);
                continue;
            }
            for (int index = 1; index < names.size() - 1; index++) {
                appendMeshFace(element, List.of(names.get(0), names.get(index), names.get(index + 1)),
                        transform, definition, face, material, root, vertices, meshes);
            }
        }
    }

    private void appendMeshFace(BbModelDefinition.Element element, List<String> names,
                                BlockBenchTransform transform, BbModelDefinition definition,
                                BbModelDefinition.MeshFace face, Material material, Bone root,
                                List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f[] positions = new Vector3f[names.size()];
        Vector2f[] uvs = new Vector2f[names.size()];
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            Vector3f position = element.vertices().get(name);
            Vector2f uv = face.uvs().get(name);
            if (position == null || uv == null) return;
            positions[index] = new Vector3f(position).mul(1.0f / 16.0f);
            // MCTextureData divides these Blockbench pixel coordinates by the loaded image size.
            uvs[index] = new Vector2f(uv);
        }
        appendFace(positions, uvs, transform, material, root, vertices, meshes);
    }

    static List<String> sortMeshFaceVertices(Map<String, Vector3f> vertices, List<String> names) {
        if (names.size() != 4) return names;
        Vector3f first = vertices.get(names.get(0));
        Vector3f second = vertices.get(names.get(1));
        Vector3f third = vertices.get(names.get(2));
        Vector3f fourth = vertices.get(names.get(3));
        if (first == null || second == null || third == null || fourth == null) return names;

        if (isCrossedQuad(second, third, first, fourth)) {
            return List.of(names.get(2), names.get(0), names.get(1), names.get(3));
        }
        if (isCrossedQuad(first, second, third, fourth)) {
            return List.of(names.get(0), names.get(2), names.get(1), names.get(3));
        }
        return names;
    }

    private static boolean isCrossedQuad(Vector3f baseFirst, Vector3f baseSecond,
                                         Vector3f top, Vector3f check) {
        Vector3f line = new Vector3f(baseSecond).sub(baseFirst);
        float lengthSquared = line.lengthSquared();
        if (lengthSquared == 0.0f) return false;
        float projection = new Vector3f(top).sub(baseFirst).dot(line) / lengthSquared;
        Vector3f projectedPoint = new Vector3f(baseFirst).fma(projection, line);
        Vector3f normal = projectedPoint.sub(top);
        return normal.dot(new Vector3f(check).sub(baseSecond)) > 0.0f;
    }

    private void appendFace(Vector3f[] positions, Vector2f[] uvs, BlockBenchTransform transform, Material material, Bone root,
                            List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f[] transformed = new Vector3f[positions.length];
        for (int index = 0; index < positions.length; index++) transformed[index] = transform.apply(positions[index]);
        Vector3f cross = new Vector3f(transformed[1]).sub(transformed[0])
                .cross(new Vector3f(transformed[2]).sub(transformed[0]));
        // Zero-area faces (zero-thickness panels exported by Blockbench) have a zero-length cross product;
        // normalize() would yield NaN normal components that poison the shader's lighting (black geometry).
        // The face is invisible anyway — skip it.
        if (cross.lengthSquared() <= 1e-12f) {
            return;
        }
        Vector3f normal = cross.normalize();
        Mesh mesh = new Mesh(new Vertex[transformed.length], normal, new Transform(), new Material[]{material}, null);
        for (int index = 0; index < transformed.length; index++) {
            Vertex vertex = new Vertex(transformed[index], null);
            vertex.addUV(mesh, material, normalizePixelUv(uvs[index], material));
            vertex.setBinding(new BoneBinding(new Pair[]{Pair.of(root, 1.0f)}, BoneBindingFunc.BDEF, null));
            mesh.getVertices()[index] = vertex;
            vertices.add(vertex);
        }
        meshes.add(mesh);
    }

    /** Converts Blockbench's pixel UV coordinates to the normalized range used by the renderer. */
    static Vector2f normalizePixelUv(Vector2f uv, Material material) {
        Texture[] textures = material.getTextures();
        if (textures.length == 0 || textures[0] == null) return new Vector2f(uv);
        float width = textures[0].getWidth();
        float height = textures[0].getHeight();
        return width > 0.0f && height > 0.0f
                ? new Vector2f(uv.x / width, uv.y / height)
                : new Vector2f(uv);
    }

    private record BlockBenchTransform(Quaternionf rotation, Vector3f absoluteOrigin, Vector3f parentPivot) {
        private static final BlockBenchTransform IDENTITY = new BlockBenchTransform(
                new Quaternionf(), new Vector3f(), new Vector3f()
        );

        private BlockBenchTransform child(Vector3f pivot, Vector3f localRotation) {
            Vector3f scaledPivot = new Vector3f(pivot).mul(1.0f / 16.0f);
            Vector3f origin = scaledPivot.sub(parentPivot).rotate(rotation).add(absoluteOrigin);
            Quaternionf combinedRotation = new Quaternionf(rotation).mul(QuaternionHelper.fromXYZDegrees(localRotation));
            return new BlockBenchTransform(combinedRotation, origin, scaledPivot);
        }

        private Vector3f apply(Vector3f localPosition) {
            return new Vector3f(localPosition).rotate(rotation).add(absoluteOrigin);
        }
    }

    private static Vector3f[] cubeFacePositions(lib.kasuga.rendering.models.mc.util.Direction direction, Vector3f min, Vector3f max) {
        return switch (direction) {
            case DOWN -> new Vector3f[]{new Vector3f(min.x, min.y, max.z), new Vector3f(max.x, min.y, max.z), new Vector3f(max.x, min.y, min.z), new Vector3f(min.x, min.y, min.z)};
            case UP -> new Vector3f[]{new Vector3f(min.x, max.y, min.z), new Vector3f(max.x, max.y, min.z), new Vector3f(max.x, max.y, max.z), new Vector3f(min.x, max.y, max.z)};
            case NORTH -> new Vector3f[]{new Vector3f(max.x, min.y, min.z), new Vector3f(min.x, min.y, min.z), new Vector3f(min.x, max.y, min.z), new Vector3f(max.x, max.y, min.z)};
            case SOUTH -> new Vector3f[]{new Vector3f(min.x, min.y, max.z), new Vector3f(max.x, min.y, max.z), new Vector3f(max.x, max.y, max.z), new Vector3f(min.x, max.y, max.z)};
            case WEST -> new Vector3f[]{new Vector3f(min.x, min.y, min.z), new Vector3f(min.x, min.y, max.z), new Vector3f(min.x, max.y, max.z), new Vector3f(min.x, max.y, min.z)};
            case EAST -> new Vector3f[]{new Vector3f(max.x, min.y, max.z), new Vector3f(max.x, min.y, min.z), new Vector3f(max.x, max.y, min.z), new Vector3f(max.x, max.y, max.z)};
        };
    }

    static Vector2f[] rectangularUvs(float[] uv, int rotation) {
        Vector2f[] result = {
                new Vector2f(uv[0], uv[1]), new Vector2f(uv[2], uv[1]),
                new Vector2f(uv[2], uv[3]), new Vector2f(uv[0], uv[3])
        };
        int turns = Math.floorMod(rotation / 90, 4);
        if (turns == 0) return result;
        Vector2f[] rotated = new Vector2f[4];
        for (int index = 0; index < 4; index++) rotated[index] = result[(index + turns) % 4];
        return rotated;
    }

    static ResourceLocation resolveTextureLocation(ResourceLocation model, String source, String name) {
        ResourceLocation sourceLocation = source.contains(":")
                ? ResourceLocation.parse(source)
                : ResourceLocation.fromNamespaceAndPath(model.getNamespace(), source.isBlank() ? name : source);
        String path = sourceLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - ".png".length());
        return ResourceLocation.fromNamespaceAndPath(sourceLocation.getNamespace(), path);
    }

    private static ResourceLocation embeddedTextureLocation(ResourceLocation model, int index) {
        String path = model.getPath().replaceAll("[^a-z0-9_./-]", "_").replace(".bbmodel", "");
        // Atlas sprite identifiers omit the conventional textures/ directory and .png suffix.
        // Keeping those filesystem markers here makes the stitched sprite lookup miss.
        return ResourceLocation.fromNamespaceAndPath(model.getNamespace(), "bbmodel/" + path + "_" + index);
    }

    private static BufferedImage decodeImage(String source, ResourceLocation model) {
        int separator = source.indexOf(',');
        if (separator < 0) throw new IllegalArgumentException("Invalid embedded texture in " + model);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(source.substring(separator + 1))));
            if (image == null) throw new IllegalArgumentException("Unsupported embedded texture in " + model);
            return image;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unable to decode embedded texture in " + model, exception);
        }
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @Override public MaterialSetBuilder<Integer> materialSetBuilder() { return materialSetBuilder; }
    @Override public String getName() { return name; }
    @Override public boolean isValidInput(Object input) { return input instanceof String; }
    @Override public HashMap<SourceType, HashMap<String, SourceManager<?>>> getSidedSources() { return sidedSources; }
    @Override public Texture loadTexture(Object textureIdentifier) { return materialSetBuilder.getTexture((Integer) textureIdentifier); }
}
