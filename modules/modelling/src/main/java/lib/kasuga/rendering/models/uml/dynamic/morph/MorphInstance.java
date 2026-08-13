package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.blender.BlenderType;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.IMorphHolder;
import lib.kasuga.rendering.models.uml.dynamic.morph.results.*;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;

/**
 * Dynamic morph state — tracks activation factors, dirty elements via {@link BitSet},
 * and produces {@link lib.kasuga.rendering.models.uml.dynamic.morph.results.IMorphResult} objects for the render pipeline.
 */
@Getter
public class MorphInstance<IdType> {

    protected final Morph<IdType> morph;
    protected final Map<MorphType<?, ?, IdType>, Float[]> activeFactors;

    // ── Model element arrays (direct references, no copy) ──────────
    protected final Vertex[] vertices;
    protected final Mesh[] meshes;
    protected final Bone[] bones;
    protected final Material[] materials;

    // ── Forward index (element → array index) ─────────────────────
    protected final Map<Vertex, Integer> vertexIndex;
    protected final Map<Mesh, Integer> meshIndex;
    protected final Map<Material, Integer> materialIndex;

    // ── Dirty tracking (BitSet) ────────────────────────────────────
    protected final BitSet dirtyVertices;
    protected final BitSet dirtyMeshes;
    protected final BitSet dirtyBones;
    protected final BitSet dirtyMaterials;

    // ── Last-updated (BitSet) ─────────────────────────────────────
    protected final BitSet lastUpdatedVertices;
    protected final BitSet lastUpdatedBones;
    protected final BitSet lastUpdatedMeshes;
    protected final BitSet lastUpdatedMaterials;

    // ── Morph results (lazy Map) ───────────────────────────────────
    protected final Map<Vertex, VertexResult> vertexResults;
    protected final Map<Bone, BoneResult> boneResults;
    protected final Map<Mesh, MeshResult> meshResults;
    protected final Map<Material, MaterialResult> materialResults;
    protected final Map<Bone, Transform> transformCache;

    @Setter
    protected byte resultMappingType;

    public MorphInstance(Morph<IdType> morph) {
        this.morph = morph;
        this.activeFactors = new HashMap<>();
        this.transformCache = new HashMap<>();

        Model model = morph.getModel();
        this.vertices = model.getVertices();
        this.meshes = model.getMeshes();
        this.bones = model.getBones();
        this.materials = model.getMaterialSet().getMaterials();

        int vc = vertices.length,
                mc = meshes.length,
                bc = bones.length,
                mac = materials.length;

        this.vertexIndex = new HashMap<>(vc);
        for (int i = 0; i < vc; i++) vertexIndex.put(vertices[i], i);
        this.meshIndex = new HashMap<>(mc);
        for (int i = 0; i < mc; i++) meshIndex.put(meshes[i], i);
        this.materialIndex = new HashMap<>(mac);
        for (int i = 0; i < mac; i++) materialIndex.put(materials[i], i);

        this.dirtyVertices = new BitSet(vc);
        this.dirtyMeshes = new BitSet(mc);
        this.dirtyBones = new BitSet(bc);
        this.dirtyMaterials = new BitSet(mac);

        this.lastUpdatedVertices = new BitSet(vc);
        this.lastUpdatedBones = new BitSet(bc);
        this.lastUpdatedMeshes = new BitSet(mc);
        this.lastUpdatedMaterials = new BitSet(mac);

        this.vertexResults = new HashMap<>();
        this.boneResults = new HashMap<>();
        this.meshResults = new HashMap<>();
        this.materialResults = new HashMap<>();

        this.resultMappingType = (byte) 0b1011;
        initialInstance();
    }

    // ── Init ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void innerInitGroup(GroupMorph<?> group, float parentFactor) {
        for (IMorphHolder<?, ?> holder : group.getSubHolders()) {
            Float f = group.getFactors().get(holder);
            if (f == null) f = 0F;
            if (holder.isGroup()) {
                innerInitGroup((GroupMorph<?>) holder, f * parentFactor);
            } else {
                MorphType<?, ?, IdType> proto = (MorphType<?, ?, IdType>) holder.getMorphPrototype();
                if (proto != null) activeFactors.putIfAbsent(proto, new Float[]{0F, f * parentFactor});
            }
        }
    }

    protected void initialInstance() {
        for (GroupMorph<IdType> gm : morph.getGroupMorphs().values())
            innerInitGroup(gm, 1F);
        for (Set<MorphType<Vertex, ?, IdType>> s : morph.getVertexMorphs().values())
            for (MorphType<Vertex, ?, IdType> mt : s) activeFactors.putIfAbsent(mt, new Float[]{0F, 1F});
        for (Set<MorphType<Mesh, ?, IdType>> s : morph.getMeshMorphs().values())
            for (MorphType<Mesh, ?, IdType> mt : s) activeFactors.putIfAbsent(mt, new Float[]{0F, 1F});
        for (Set<MorphType<Bone, ?, IdType>> s : morph.getBoneMorphs().values())
            for (MorphType<Bone, ?, IdType> mt : s) activeFactors.putIfAbsent(mt, new Float[]{0F, 1F});
        for (Set<MorphType<Material, ?, IdType>> s : morph.getMaterialMorphs().values())
            for (MorphType<Material, ?, IdType> mt : s) activeFactors.putIfAbsent(mt, new Float[]{0F, 1F});
    }

    // ── Activation ────────────────────────────────────────────────

    public boolean activateMorph(IdType id, float value) { return activateMorph(id, value, 1F); }

    @SuppressWarnings("unchecked")
    public boolean activateMorph(IdType id, float value, float factor) {
        GroupMorph<IdType> group = morph.getGroup(id);
        if (group != null) return activateGroup(group, Math.clamp(value, 0f, 1f), factor);

        List<MorphType<?, ?, IdType>> definitions = morph.getMorphs(id);
        if (definitions.isEmpty()) return false;
        float v = Math.clamp(value, 0f, 1f);
        boolean applied = false;
        for (MorphType<?, ?, IdType> mt : definitions) {
            if (mt instanceof FlipMorph<?> flip) {
                applied |= applySingle((MorphType<?, ?, IdType>) flip.getReferenceMorph(), 1f - v, factor);
            } else {
                applied |= applySingle(mt, v, factor);
            }
        }
        return applied;
    }

    @SuppressWarnings("unchecked")
    private boolean activateGroup(GroupMorph<IdType> group, float value, float factor) {
        boolean any = false;
        for (IMorphHolder<?, IdType> h : group.getSubHolders()) {
            Float f = group.getFactors().get(h);
            if (f == null) f = 1F;
            if (h.isGroup()) any |= activateGroup((GroupMorph<IdType>) h, value, f * factor);
            else {
                MorphType<?, ?, IdType> proto = (MorphType<?, ?, IdType>) h.getMorphPrototype();
                if (proto != null) any |= applySingle(proto, value, f * factor);
            }
        }
        return any;
    }

    public void deactivateMorph(IdType id) { activateMorph(id, 0F, 1F); }
    public void deactivateMorph(IdType id, float factor) { activateMorph(id, 0F, factor); }

    private boolean applySingle(MorphType<?, ?, IdType> mt, float value, float factor) {
        activeFactors.put(mt, new Float[]{value, factor});
        markDirty(mt.getOriginal());
        return true;
    }

    private void markDirty(Object original) {
        if (original instanceof Vertex v) {
            Integer idx = vertexIndex.get(v);
            if (idx != null) dirtyVertices.set(idx);
        } else if (original instanceof Mesh m) {
            Integer idx = meshIndex.get(m);
            if (idx != null) dirtyMeshes.set(idx);
        } else if (original instanceof Bone b) {
            dirtyBones.set(b.getIndex());
        } else if (original instanceof Material mat) {
            Integer idx = materialIndex.get(mat);
            if (idx != null) dirtyMaterials.set(idx);
        }
    }

    // ── Result accessors ──────────────────────────────────────────

    @Nullable public VertexResult getVertexResult(Vertex v) { return vertexResults.get(v); }
    @Nullable public BoneResult getBoneResult(Bone b) { return boneResults.get(b); }
    @Nullable public MeshResult getMeshResult(Mesh m) { return meshResults.get(m); }
    @Nullable public MaterialResult getMaterialResult(Material m) { return materialResults.get(m); }
    @Nullable public Transform getCachedTransform(Bone b) { return transformCache.get(b); }

    public void clearTransformCache() {
        transformCache.clear();
    }

    public boolean isVertexDirtyAt(int index) { return dirtyVertices.get(index); }
    public boolean isBoneDirtyAt(int index)    { return dirtyBones.get(index); }
    public boolean isMeshDirtyAt(int index)    { return dirtyMeshes.get(index); }
    public boolean isMaterialDirtyAt(int index){ return dirtyMaterials.get(index); }

    // ── Dirty queries ─────────────────────────────────────────────

    public boolean isDirty() {
        return !dirtyVertices.isEmpty() || !dirtyBones.isEmpty()
                || !dirtyMeshes.isEmpty() || !dirtyMaterials.isEmpty();
    }

    public boolean shouldUpdate() {
        return !lastUpdatedBones.isEmpty() || !lastUpdatedVertices.isEmpty() ||
                !lastUpdatedMeshes.isEmpty() || !lastUpdatedMaterials.isEmpty();
    }

    public void clearLastChanged() {
        lastUpdatedBones.clear();
        lastUpdatedVertices.clear();
        lastUpdatedMeshes.clear();
        lastUpdatedMaterials.clear();
    }

    public boolean isVerticesDirty()  { return !dirtyVertices.isEmpty(); }
    public boolean isBonesDirty()     { return !dirtyBones.isEmpty(); }
    public boolean isMeshesDirty()    { return !dirtyMeshes.isEmpty(); }
    public boolean isMaterialsDirty() { return !dirtyMaterials.isEmpty(); }

    // ── Update ────────────────────────────────────────────────────

    public void update() {
        if (!isDirty()) return;

        // Reset all accumulated deltas before this update cycle
        vertexResults.values().forEach(VertexResult::reset);
        boneResults.values().forEach(BoneResult::reset);
        meshResults.values().forEach(MeshResult::reset);
        materialResults.values().forEach(MaterialResult::reset);

        if (isVerticesDirty()) {
            for (int i = dirtyVertices.nextSetBit(0); i >= 0; i = dirtyVertices.nextSetBit(i + 1)) {
                Vertex v = vertices[i];
                Set<MorphType<Vertex, ?, IdType>> set = morph.getVertexMorphs().get(v);
                if (set == null || set.isEmpty()) continue;
                VertexResult r = vertexResults.computeIfAbsent(v, VertexResult::new);
                morphVertex(v, set, r);
                lastUpdatedVertices.set(i);
            }
        }

        if (isBonesDirty()) {
            for (int i = dirtyBones.nextSetBit(0); i >= 0; i = dirtyBones.nextSetBit(i + 1)) {
                Bone b = bones[i];
                Set<MorphType<Bone, ?, IdType>> set = morph.getBoneMorphs().get(b);
                if (set == null || set.isEmpty()) continue;
                BoneResult r = boneResults.computeIfAbsent(b, BoneResult::new);
                morphBone(b, set, r);
                lastUpdatedBones.set(i);
            }
        }

        if (isMeshesDirty()) {
            for (int i = dirtyMeshes.nextSetBit(0); i >= 0; i = dirtyMeshes.nextSetBit(i + 1))
                lastUpdatedMeshes.set(i);
        }

        if (isMaterialsDirty()) {
            for (int i = dirtyMaterials.nextSetBit(0); i >= 0; i = dirtyMaterials.nextSetBit(i + 1)) {
                Material m = materials[i];
                Set<MorphType<Material, ?, IdType>> set = morph.getMaterialMorphs().get(m);
                if (set == null || set.isEmpty()) continue;
                MaterialResult r = materialResults.computeIfAbsent(m, MaterialResult::new);
                morphMaterial(m, set, r);
                lastUpdatedMaterials.set(i);
            }
        }

        clearAllDirtyMarks();
    }

    // ── Element morphing → populates Result objects ───────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void morphVertex(Vertex v, Set<MorphType<Vertex, ?, IdType>> set, VertexResult r) {
        for (MorphType<Vertex, ?, IdType> mt : set) {
            Float[] val = activeFactors.get(mt);
            if (val == null || val[0] <= 0f) continue;

            if (mt instanceof VertexPosMorph) {
                r.addPosition((Vector3f) mt.morph(v, val[0], val[1]));
            } else if (mt instanceof VertexNormalMorph<?> vnm) {
                r.addNormal(vnm.getMesh(), (Vector3f) mt.morph(v, val[0], val[1]));
            } else if (mt instanceof VertexUvMorph<?> uvm) {
                r.addUv(uvm.getMesh(), uvm.getMaterial(), (Vector2f) mt.morph(v, val[0], val[1]));
            } else if (mt instanceof VertexTangentMorph) {
                r.addTangent((Vector4f) mt.morph(v, val[0], val[1]));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void morphBone(Bone b, Set<MorphType<Bone, ?, IdType>> set, BoneResult r) {
        for (MorphType<Bone, ?, IdType> mt : set) {
            Float[] val = activeFactors.get(mt);
            if (val == null || val[0] <= 0f) continue;
            if (mt instanceof BoneTransformMorph) {
                r.setTransform((Transform) mt.morph(b, val[0], val[1]));
            }
        }
        transformCache.put(b, r.getTransform());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void morphMaterial(Material m, Set<MorphType<Material, ?, IdType>> set, MaterialResult r) {
        for (MorphType<Material, ?, IdType> mt : set) {
            Float[] val = activeFactors.get(mt);
            if (val == null || val[0] <= 0f) continue;

            if (mt instanceof MaterialColorMorph<?> colorMorph) {
                r.addColor((Vector4f) mt.morph(m, val[0], val[1]), colorMorph.getBlendMode());
            } else if (mt instanceof MaterialSpecularMorph) {
                r.addSpecular((Vector4f) mt.morph(m, val[0], val[1]), BlendMode.ADD);
            } else if (mt instanceof MaterialAmbientMorph) {
                r.addAmbient((Vector4f) mt.morph(m, val[0], val[1]), BlendMode.ADD);
            } else if (mt instanceof MaterialEdgeColorMorph) {
                r.addEdgeColor((Vector4f) mt.morph(m, val[0], val[1]), BlendMode.ADD);
            } else if (mt instanceof SpriteFrameMorph<?> sm) {
                r.setSpriteFrame(sm.getSpriteSetIndex(), (Integer) mt.morph(m, val[0], val[1]));
            } else if (mt instanceof MaterialFrameMorph) {
                r.setMaterialFrame((Integer) mt.morph(m, val[0], val[1]));
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────

    public void clearAllDirtyMarks() {
        dirtyVertices.clear(); dirtyBones.clear();
        dirtyMeshes.clear(); dirtyMaterials.clear();
    }

    public void clearResults() {
        vertexResults.values().forEach(VertexResult::reset);
        boneResults.values().forEach(BoneResult::reset);
        meshResults.values().forEach(MeshResult::reset);
        materialResults.values().forEach(MaterialResult::reset);
        transformCache.clear();
    }

    public void resetLastUpdated() {
        lastUpdatedVertices.clear(); lastUpdatedBones.clear();
        lastUpdatedMeshes.clear(); lastUpdatedMaterials.clear();
    }

    // ── Blend-on-read output ports ────────────────────────────────

    /** Morphed position = original + delta; returns dest for chaining. */
    public Vector3f getVertexPos(Vertex vertex, Vector3f dest) {
        VertexResult r = vertexResults.get(vertex);
        return (r != null && r.getPosition() != null)
                ? dest.set(vertex.getPosition()).add(r.getPosition())
                : dest.set(vertex.getPosition());
    }

    /** Morphed normal per mesh = normalize(original + delta). */
    public Vector3f getVertexNormal(Vertex vertex, Mesh mesh, Vector3f dest) {
        VertexResult r = vertexResults.get(vertex);
        if (r != null) {
            Vector3f delta = r.getNormals().get(mesh);
            if (delta != null) {
                dest.set(vertex.getNormal(mesh)).add(delta);
                if (dest.lengthSquared() > 0f) dest.normalize();
                return dest;
            }
        }
        return dest.set(vertex.getNormal(mesh));
    }

    /** Morphed UV per mesh+material = original + delta. */
    public Vector2f getVertexUv(Vertex vertex, Mesh mesh, Material material, Vector2f dest) {
        VertexResult r = vertexResults.get(vertex);
        if (r != null) {
            Map<Material, Vector2f> matMap = r.getUvs().get(mesh);
            if (matMap != null) {
                Vector2f delta = matMap.get(material);
                if (delta != null) {
                    Vector2f uv = vertex.getUV(mesh, material);
                    return dest.set(uv != null ? uv : new Vector2f()).add(delta);
                }
            }
        }
        Vector2f uv = vertex.getUV(mesh, material);
        return dest.set(uv != null ? uv : new Vector2f());
    }

    /** Tangent delta, or null. */
    @Nullable
    public void getVertexTangent(Vertex vertex, Vector4f dest) {
        VertexResult r = vertexResults.get(vertex);
        if (r == null || r.getTangent() == null) return;
        dest.set(r.getTangent());
    }

    /** Morphed bone transform, or original. */
    public void getBoneTransform(Bone bone, Transform dest) {
        BoneResult r = boneResults.get(bone);
        dest.mul(bone.getTransform());
        if (r == null || r.getTransform() == null) return;
        dest.mul(r.getTransform());
    }

    /** Material color: MULTIPLY → original × delta, ADD → original + delta. Falls back to white. */
    public void getMaterialColor(Material material, Sprite sprite, Vector4f dest) {
        MaterialResult r = materialResults.get(material);
        if (r != null && (r.getColorMultiply() != null || r.getColorAdd() != null)) {
            dest.set(sprite.color);
            if (r.getColorMultiply() != null) dest.mul(r.getColorMultiply());
            if (r.getColorAdd() != null) dest.add(r.getColorAdd());
            return;
        }
        dest.set(sprite.color);
    }

    /** Material specular: ADD → default + delta, MULTIPLY → default × delta. Falls back to default. */
    public void getMaterialSpecular(Material m, Sprite sprite, Vector4f dest) {
        MaterialResult r = materialResults.get(m);
        if (r != null && r.getSpecular() != null) {
            if (r.getSpecularBlendMode() == BlendMode.MULTIPLY) {
                dest.set(sprite.specular).mul(r.getSpecular());
            } else {
                dest.set(sprite.specular).add(r.getSpecular());
            }
            return;
        }
        dest.set(sprite.specular);
    }

    /** Material ambient: ADD → default + delta, MULTIPLY → default × delta. Falls back to default. */
    public void getMaterialAmbient(Material m, Sprite sprite, Vector4f dest) {
        MaterialResult r = materialResults.get(m);

        if (r != null && r.getAmbient() != null) {
            if (r.getAmbientBlendMode() == BlendMode.MULTIPLY) {
                dest.set(sprite.ambient).mul(r.getAmbient());
            } else {
                dest.set(sprite.ambient).add(r.getAmbient());
            }
            return;
        }
        dest.set(sprite.ambient);
    }

    public void getMaterialUv(Material m, Vector2f uv0, Vector2f uv1, Vector2f uv2, Vector2f uv3) {

    }

//    /** Material edge color: ADD → default + delta, MULTIPLY → default × delta. Falls back to default. */
//    public Vector4f getMaterialEdgeColor(Material m, Sprite sprite, Vector4f dest) {
//        MaterialResult r = materialResults.get(m);
//        if (r != null && r.getEdgeColor() != null) {
//            if (r.getEdgeColorBlendMode() == BlendMode.MULTIPLY)
//                return dest.set().mul(r.getEdgeColor());
//            else
//                return dest.set(defaultEdgeColor).add(r.getEdgeColor());
//        }
//        return dest.set(defaultEdgeColor);
//    }
//
    @NotNull
    public Integer getMaterialSpriteFrame(Material m) {
        MaterialResult r = materialResults.get(m);
        return r != null ? r.getSpriteFrame() : -1;
    }

    @NotNull
    public Integer getMaterialFrameIndex(Material m) {
        MaterialResult r = materialResults.get(m);
        return r != null ? r.getMaterialFrame() : -1;
    }

    public Sprite getSprite(Material material) {
        int idx = getMaterialFrameIndex(material);
        if (idx == -1) return null;
        return material.getSprites().get(idx)
                .getSprite(getMaterialSpriteFrame(material));
    }

    // ── Blender stub ──────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    public <T> BlenderType<T> getBlender(T original, Set<? extends MorphType<T, ?, IdType>> set,
                                         List<IMorphResult<T>> results) { return null; }
}
