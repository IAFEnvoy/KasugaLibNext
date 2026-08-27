package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.morph.MorphInstance;
import lib.kasuga.rendering.models.uml.dynamic.morph.MorphResult;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelInstanceData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.material.animators.MaterialAnimation;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonInstanceData;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@Getter
public class ModelInstance implements AutoCloseable {

    private final Model model;

    private final SkeletonInstance skeletonInstance;

    private final MaterialSetInstance materialInstance;

    @Nullable
    private ModelInstanceData data;

    @NotNull
    private MorphInstance morph;

    public ModelInstance(Model model, @Nullable Transform initTransform,
                         @Nullable ModelInstanceData data,
                         @Nullable SkeletonInstanceData skeletonInstanceData,
                         @Nullable MaterialSetInstance materialInstance,
                         @Nullable MorphInstance morph) {
        this.model = model;
        this.data = data;
        this.morph = morph == null ? new MorphInstance<>(model.getMorph()) : morph;
        this.skeletonInstance = new SkeletonInstance(this, model.getSkeleton(), initTransform, skeletonInstanceData);
        this.materialInstance = materialInstance;
    }

    public SpriteSet getMaterialFrame(Material mat) {
        if (materialInstance == null || !materialInstance.getActiveAnimations().containsKey(mat))
            return mat.getSprites().getFirst();
        MaterialAnimation anim = materialInstance.getActiveAnimations().get(mat);
        return anim.getCurrentSprite();
    }

    public void forceUpdate() {
        skeletonInstance.setShouldUpdate(true);
    }

    public void updateImmediate() {
        forceUpdate();
        skeletonInstance.updateTransform();
        morph.update();
    }

    public Vertex getVertex(Vertex original) {
        return morph.getVertex(original);
    }

    public Mesh getMesh(Mesh original) {
        return morph.getMesh(original);
    }

    public Bone getBone(Bone original) {
        return morph.getBone(original);
    }

    public Material getMaterial(Material original) {
        return morph.getMaterial(original);
    }

    public Map<Vertex, Vertex> getMorphedVertices() {
        return morph.getVertexCache();
    }

    public Map<Mesh, Mesh> getMorphedMeshes() {
        return morph.getMeshCache();
    }

    public Map<Bone, Bone> getMorphedBones() {
        return morph.getBoneCache();
    }

    public Map<Material, Material> getMorphedMaterials() {
        return morph.getMaterialCache();
    }

    public void setMorphResultMappingType(byte type) {
        morph.setResultMappingType((byte) (type & 0x0f));
    }

    public Map<Vertex, MorphResult<Vertex>> getMorphedAffectedVertices() {
        return morph.getNewlyMorphedResults();
    }

    @Override
    public void close() throws Exception {}
}
