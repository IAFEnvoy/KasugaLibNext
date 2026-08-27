package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.ByteStreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.TextLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone.BoneChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone.BoneSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header.HeaderChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header.HeaderInfoChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.material.MaterialChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.material.MaterialSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.mesh.MeshChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.mesh.MeshSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.texture.TextureSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.vertex.VertexChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.vertex.VertexSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class PMXLoader<InputType, OutputIdentifier, TextureIdentifier, M extends ContextData<M>>
        extends ByteStreamLoader<InputType, OutputIdentifier, TextureIdentifier, M> {

    public static final HeaderInfoChunk HEADER_INFO_CHUNK = new HeaderInfoChunk();

    public static final HeaderChunk HEADER_CHUNK = new HeaderChunk(HEADER_INFO_CHUNK);

    private final MeshSetChunk meshSetChunk;

    private final VertexSetChunk vertexSetChunk;

    private final TextureSetChunk textureSetChunk;

    private final MaterialSetChunk materialSetChunk;

    private final TextLoader textLoader;

    private final BoneSetChunk boneSetChunk;

    @Getter
    private boolean textureLoading;

    @Getter
    private final String name;

    @Getter
    private PmxHeader header;

    @Getter
    private List<PmxVertex> vertices;

    @Getter
    private List<PmxMesh> meshes;

    @Getter
    private List<String> textures;

    @Getter
    private List<PmxMaterial> materials;

    @Getter
    private List<PmxBone> bones;

    public PMXLoader(String name) {
        this.name = name;
        this.textLoader = new TextLoader(StandardCharsets.UTF_8);
        registerLoader(HEADER_CHUNK);
        vertexSetChunk = new VertexSetChunk();
        registerLoader(vertexSetChunk);
        meshSetChunk = new MeshSetChunk();
        registerLoader(meshSetChunk);
        textureSetChunk = new TextureSetChunk();
        registerLoader(textureSetChunk);
        materialSetChunk = new MaterialSetChunk();
        registerLoader(materialSetChunk);
        boneSetChunk = new BoneSetChunk();
        registerLoader(boneSetChunk);
        textureLoading = false;
    }

    @Override
    public void afterLoader(StreamLoader loader, ByteBuffer buffer, Object result, SerialContext<M> context) {
        if (loader == HEADER_CHUNK) {
            this.header = (PmxHeader) result;
            getTextLoader();
            vertexSetChunk.setVertexChunk(new VertexChunk(this));
            meshSetChunk.setMeshChunk(new MeshChunk(this));
            textureSetChunk.setTextLoader(textLoader);
            materialSetChunk.setMaterialChunk(new MaterialChunk(this));
            boneSetChunk.setBoneChunk(new BoneChunk(this));
        } else if (loader == vertexSetChunk) {
            this.vertices = (List<PmxVertex>) result;
        } else if (loader == meshSetChunk) {
            this.meshes = (List<PmxMesh>) result;
        } else if (loader == textureSetChunk) {
            this.textures = (List<String>) result;
        } else if (loader == materialSetChunk) {
            this.materials = (List<PmxMaterial>) result;
        } else if (loader == boneSetChunk) {
            this.bones = (List<PmxBone>) result;
        }
    }

    @Override
    public void build(Map<OutputIdentifier, Model> map, OutputIdentifier outputIdentifier, ByteBuffer buffer, SerialContext<M> context) {
        MaterialSetBuilder builder = materialSetBuilder();
        registerTextures(builder, textures);
        registerMaterials(builder, materials);
        Map<Object, Material> materialMap = Map.copyOf(builder.getMaterialByIds());
        MaterialSet materialSet = builder.endMaterialSet();

        if (materialMap.size() != materials.size()) {
            throw new IllegalStateException("Material count mismatch: expected " + materials.size() + ", got " + materialMap.size());
        }

        Map<PmxVertex, List<PmxVertex>> vertexMapping = new HashMap<>();
        List<PmxVertex> sortedVertices = new ArrayList<>();
        int[] mappings = new int[vertices.size()];
        int i = 0;
        for (PmxVertex v : vertices) {
            if (vertexMapping.containsKey(v)) {
                List<PmxVertex> duplicates = vertexMapping.get(v);
                duplicates.add(v);
                mappings[i] = sortedVertices.indexOf(v);
            } else {
                ArrayList<PmxVertex> duplicates = new ArrayList<>();
                duplicates.add(v);
                vertexMapping.put(v, duplicates);
                sortedVertices.add(v);
                mappings[i] = sortedVertices.size() - 1;
            }
            i++;
        }

        int mappingSize = vertexMapping.size();
        Vertex[] verticesArray = new Vertex[mappingSize];
        for (i = 0; i < sortedVertices.size(); i++) {
            PmxVertex v = sortedVertices.get(i);
            List<PmxVertex> duplicates = vertexMapping.get(v);
            Vertex vertex = getVertex(v, duplicates);
            verticesArray[i] = vertex;
        }

        Mesh[] meshesArray = new Mesh[meshes.size()];
        for (i = 0; i < meshes.size(); i++) {
            PmxMesh m = meshes.get(i);
            Vertex v1 = verticesArray[mappings[m.getVertex1().intValue()]];
            Vertex v2 = verticesArray[mappings[m.getVertex2().intValue()]];
            Vertex v3 = verticesArray[mappings[m.getVertex3().intValue()]];
            Mesh mesh = getMesh(v1, v2, v3, m);
            meshesArray[i] = mesh;
        }

        int offset = 0;
        for (i = 0; i < materials.size(); i++) {
            PmxMaterial m = materials.get(i);
            int count = m.meshCount / 3;
            Material material = materialMap.get(m);
            for (int j = offset; j < offset + count; j++) {
                PmxMesh pMesh = meshes.get(j);
                Mesh mesh = meshesArray[j];
                mesh.getMaterials()[0] = material;
                int mesh1Val = pMesh.getVertex1().intValue();
                setUVAndNormalForVertex(
                        mappings[mesh1Val],
                        mesh1Val, material, verticesArray, mesh
                );
                int mesh2Val = pMesh.getVertex2().intValue();
                setUVAndNormalForVertex(
                        mappings[mesh2Val],
                        mesh2Val, material, verticesArray, mesh
                );
                int mesh3Val = pMesh.getVertex3().intValue();
                setUVAndNormalForVertex(
                        mappings[mesh3Val],
                        mesh3Val, material, verticesArray, mesh
                );
            }
            offset += count;
        }

        Bone[] boneArray = new Bone[bones.size()];
        Bone rootBone = null;
        List<Bone> childOfRoot = new ArrayList<>();
        boolean hasMultiRootBone = false;
        for (i = 0; i < bones.size(); i++) {
            PmxBone b = bones.get(i);
            Bone bone = getBone(bones, b);
            boneArray[i] = bone;
            if (b.parentBoneIndex.intValue() == -1) {
                if (rootBone == null) {
                    rootBone = bone;
                    continue;
                }
                if (hasMultiRootBone) {
                    childOfRoot.add(bone);
                    continue;
                }
                PmxBone dummyRoot = new PmxBone(
                        "dummy_root",
                        "dummy_root",
                        new Vector3f(),
                        -1,
                        0,
                        new PmxBoneFlags(),
                        new Vector3f(),
                        null,
                        null,
                        null,
                        -1,
                        null
                );
                childOfRoot.add(rootBone);
                rootBone = new Bone("dummy_root", new Transform(), dummyRoot);
                childOfRoot.add(bone);
                hasMultiRootBone = true;
            }
        }

        HashMap<Bone, List<Bone>> childMap = new HashMap<>();
        for (i = 0; i < bones.size(); i++) {
            PmxBone b = bones.get(i);
            Bone bone = boneArray[i];
            int parentIndex = b.parentBoneIndex.intValue();
            Bone parentBone;
            if (parentIndex == -1) {
                if (childOfRoot.contains(bone)) parentBone = rootBone;
                else continue;
            } else {
                parentBone = boneArray[parentIndex];
            }
            bone.setParent(parentBone);
            if (childMap.containsKey(parentBone)) {
                ArrayList<Bone> children = (ArrayList<Bone>) childMap.get(parentBone);
                if (!children.contains(bone)) children.add(bone);
            } else {
                childMap.put(parentBone, new ArrayList<>(List.of(bone)));
            }
        }

        for (Map.Entry<Bone, List<Bone>> entry : childMap.entrySet()) {
            Bone parent = entry.getKey();
            parent.setChildren(entry.getValue().toArray(new Bone[0]));
            entry.getValue().clear();
        }

        for (i = 0; i < sortedVertices.size(); i++) {
            PmxVertex v = sortedVertices.get(i);
            PmxBoneBinding b = v.binding;
            Pair<Bone, Float>[] weights = new Pair[b.boneWeights.size()];
            int j = 0;
            for (Map.Entry<Number, Float> entry : b.boneWeights.entrySet()) {
                int boneIndex = entry.getKey().intValue();
                if (boneIndex < 0 || boneIndex >= boneArray.length) {
                    continue;
                }
                Bone bone = boneArray[boneIndex];
                weights[j++] = Pair.of(bone, entry.getValue());
            }
            BoneBinding binding = new BoneBinding(weights, b.toBindingFunc(), b);
            verticesArray[i].setBinding(binding);
        }

        if (hasMultiRootBone) {
            Bone[] bones1 = new Bone[boneArray.length + 1];
            System.arraycopy(boneArray, 0, bones1, 1, boneArray.length);
            bones1[0] = rootBone;
            boneArray = bones1;
        }
        Skeleton skeleton = new Skeleton(boneArray, rootBone, new Anchor[0], null, new Transform());

        // TODO: 补充这里的morph
        Model model = new Model(
                verticesArray,
                meshesArray,
                boneArray,
                skeleton,
                materialSet,
                getModelData(header),
                null
        );

        vertexMapping.clear();
        sortedVertices.clear();
        childOfRoot.clear();
        map.put(outputIdentifier, model);
    }

    public Transform calculateBoneTransform(List<PmxBone> bones, PmxBone current) {
        Transform transform = new Transform();
        Object tailObject = current.tailObject;
        Vector3f dir;
        Quaternionf rotation;
        Vector3f defaultDir = new Vector3f(0, 1, 0);
        if (tailObject instanceof Vector3f vector3f) {
            if (vector3f.length() != 0) {
                dir = new Vector3f(vector3f).sub(current.position).normalize();
                rotation = new Quaternionf().rotationTo(defaultDir, dir);
            } else rotation = new Quaternionf();
        } else if (tailObject instanceof Number boneIndex) {
            int index = boneIndex.intValue();
            if (index >= 0 && index < bones.size()) {
                PmxBone bone = bones.get(index);
                Vector3f tailPos = bone.position;
                dir = new Vector3f(tailPos).sub(current.position).normalize();
                rotation = new Quaternionf().rotationTo(defaultDir, dir);
            } else rotation = new Quaternionf();
        } else rotation = new Quaternionf();

        transform.translate(current.position);
        transform.mul(rotation);
        return transform;
    }

    private void setUVAndNormalForVertex(Number vertexIndex, Number unmappedIndex, Material material, Vertex[] vertices, Mesh mesh) {
        int indexVal = vertexIndex.intValue();
        Vertex v1 = vertices[indexVal];
        PmxVertex pmxVertex = this.vertices.get(unmappedIndex.intValue());
        Map<Mesh, HashMap<Material, Vector2f>> uvMap = v1.getUvs();
        Map<Mesh, Vector3f> normalMap = v1.getNormals();
        uvMap.computeIfAbsent(mesh, m -> new HashMap<>())
                .put(material, new Vector2f(pmxVertex.uv));
        normalMap.put(mesh, new Vector3f(pmxVertex.normal));
    }

    public void registerTextures(MaterialSetBuilder builder, List<String> texturePaths) {
        textureLoading = true;
        for (String str : texturePaths) {
            TextureIdentifier identifier = getTextureIdentifier(str);
            Texture texture = loadTexture(identifier);
            builder.registerTexture(identifier, texture);
        }
    }

    public void registerMaterials(MaterialSetBuilder builder, List<PmxMaterial> materials) {
        textureLoading = false;
        for (PmxMaterial material : materials) {
            buildMaterial(builder, material);
        }
    }

    public abstract void buildMaterial(MaterialSetBuilder builder, PmxMaterial material);

    public abstract TextureIdentifier getTextureIdentifier(String texturePath);

    public abstract Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertex);

    public abstract Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh);

    public abstract Bone getBone(List<PmxBone> bones, PmxBone bone);

    public abstract @Nullable ModelData getModelData(PmxHeader header);

    public TextLoader getTextLoader() {
        checkHeaderLoaded();
        textLoader.setEncoding(header.info.encoding);
        return textLoader;
    }

    public StreamLoader getLoaderForIndexType(boolean isVertex, int index) {
        return switch (index) {
            case 2 -> isVertex ? BasicLoaders.USHORT : BasicLoaders.SHORT;
            case 4 -> BasicLoaders.INT;
            default -> isVertex ? BasicLoaders.UBYTE : BasicLoaders.BYTE;
        };
    }

    public void checkHeaderLoaded() {
        if (header == null) {
            throw new IllegalStateException("Header must be loaded before accessing index loader");
        }
    }

    public StreamLoader vertexIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(true, header.info.vertexIndexSize);
    }

    public StreamLoader materialIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.materialIndexSize);
    }

    public StreamLoader textureIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.textureIndexSize);
    }

    public StreamLoader boneIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.boneIndexSize);
    }

    public StreamLoader morphIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.morphIndexSize);
    }

    public StreamLoader rigidBodyIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.rigidBodyIndexSize);
    }
}
