package lib.kasuga.rendering.models.uml.typo.gltf;

import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.util.Map;

/** glTF metadata retained by a converted UML model. */
public record GltfModelData(GltfAsset asset, Map<Integer, Bone> boneByNode,
                            Vector3f modelScale) implements ModelData {
    public GltfModelData {
        boneByNode = Map.copyOf(boneByNode);
        modelScale = new Vector3f(modelScale);
    }

    @Override public Vector3f modelScale() { return new Vector3f(modelScale); }

    @Override public boolean isMeshTriangles() { return true; }

    public record GltfMeshData(String name, int nodeIndex, int skinIndex) implements MeshData {}
    public record GltfMaterialData(GltfAsset.Material material) implements MaterialData {}
    public record GltfTextureData(String name, BufferedImage image) implements TextureData {}
}
