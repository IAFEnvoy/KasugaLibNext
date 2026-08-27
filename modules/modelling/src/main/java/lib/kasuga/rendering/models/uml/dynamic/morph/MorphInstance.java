package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.blender.BlenderType;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@Getter
public class MorphInstance<IdType> {

    protected final Morph<IdType> morph;

    protected final Map<Vertex, Map<VertexMorph, Float[]>> vertexMorphs;

    protected final Map<Mesh, Map<MeshMorph, Float[]>> meshMorphs;

    protected final Map<Bone, Map<BoneMorph, Float[]>> boneMorphs;

    protected final Map<Material, Map<MaterialMorph, Float[]>> materialMorphs;

    protected final Map<Vertex, Vertex> vertexCache;

    protected final Map<Mesh, Mesh> meshCache;

    protected final Map<Bone, Bone>  boneCache;

    protected final Map<Material, Material> materialCache;

    protected final Map<Bone, Bone> newlyUpdatedBones;

    protected final Map<Vertex, Vertex> newlyUpdatedVertices;

    protected final Map<Mesh, Mesh> newlyUpdatedMeshes;

    protected final Map<Material, Material> newlyUpdatedMaterials;

    protected final HashSet<Vertex> dirtyVertices;

    protected final HashSet<Mesh> dirtyMeshes;

    protected final HashSet<Bone> dirtyBones;

    protected final HashSet<Material> dirtyMaterials;

    protected final Map<Vertex, MorphResult<Vertex>> newlyMorphedResults;

    @Setter
    protected byte resultMappingType;

    public MorphInstance(Morph<IdType> morph) {
        this.morph = morph;

        vertexMorphs = new HashMap<>();
        meshMorphs = new HashMap<>();
        boneMorphs = new HashMap<>();
        materialMorphs = new HashMap<>();

        vertexCache = new HashMap<>();
        meshCache = new HashMap<>();
        boneCache = new HashMap<>();
        materialCache = new HashMap<>();

        dirtyVertices = new HashSet<>();
        dirtyBones = new HashSet<>();
        dirtyMaterials = new HashSet<>();
        dirtyMeshes = new HashSet<>();

        newlyUpdatedBones = new HashMap<>();
        newlyUpdatedVertices = new HashMap<>();
        newlyUpdatedMeshes = new HashMap<>();
        newlyUpdatedMaterials = new HashMap<>();
        newlyMorphedResults = new HashMap<>();

        initialInstance();
        resultMappingType = (byte) 0b1011;
    }

    protected void innerInitGroupMorphs(GroupMorph<?> groupMorph, float parentFactor) {
        for (MorphType morphType : groupMorph.getMorphs()) {
            Float factor = groupMorph.getFactors().get(morphType);
            if (factor == null) factor = 0F;
            switch (morphType) {
                case GroupMorph gm -> {
                    innerInitGroupMorphs(gm, factor * parentFactor);
                }
                case VertexMorph vm -> {
                    vertexMorphs.computeIfAbsent(vm.getOriginal(), m -> new HashMap<>())
                            .put(vm, new Float[]{0F, factor * parentFactor});
                }
                case MeshMorph mesh -> {
                    meshMorphs.computeIfAbsent(mesh.getOriginal(), m -> new HashMap<>())
                            .put(mesh, new Float[]{0F, factor * parentFactor});
                }
                case BoneMorph bone -> {
                    boneMorphs.computeIfAbsent(bone.getOriginal(), m -> new HashMap<>())
                            .put(bone, new Float[]{0F, factor * parentFactor});
                }
                case MaterialMorph material -> {
                    materialMorphs.computeIfAbsent(material.getOriginal(), m -> new HashMap<>())
                            .put(material, new Float[]{0F, factor * parentFactor});
                }
                default -> {}
            }
        }
    }

    protected void initialInstance() {
        for (GroupMorph<?> groupMorph : morph.getGroupMorphs().values()) {
            innerInitGroupMorphs(groupMorph, 1F);
        }
        for (Map.Entry<Vertex, Set<VertexMorph>> entry : morph.getVertexMorphs().entrySet()) {
            Vertex vertex = entry.getKey();
            Set<VertexMorph> set = entry.getValue();
            Map<VertexMorph, Float[]> map;
            if (vertexMorphs.containsKey(vertex)) {
                map = vertexMorphs.get(vertex);
            } else {
                map = new HashMap<>();
                vertexMorphs.put(vertex, map);
            }
            for (VertexMorph vertexMorph : set) {
                if (map.containsKey(vertexMorph)) continue;
                map.put(vertexMorph, new Float[]{0F, 1F});
            }
        }
        for (Map.Entry<Mesh, Set<MeshMorph>> entry : morph.getMeshMorphs().entrySet()) {
            Mesh mesh = entry.getKey();
            Set<MeshMorph> set = entry.getValue();
            Map<MeshMorph, Float[]> map;
            if (meshMorphs.containsKey(mesh)) {
                map = meshMorphs.get(mesh);
            } else {
                map = new HashMap<>();
                meshMorphs.put(mesh, map);
            }
            for (MeshMorph meshMorph : set) {
                if (map.containsKey(meshMorph)) continue;
                map.put(meshMorph, new Float[]{0F, 1F});
            }
        }
        for (Map.Entry<Bone, Set<BoneMorph>> entry : morph.getBoneMorphs().entrySet()) {
            Bone bone = entry.getKey();
            Set<BoneMorph> set = entry.getValue();
            Map<BoneMorph, Float[]> map;
            if (boneMorphs.containsKey(bone)) {
                map = boneMorphs.get(bone);
            } else {
                map = new HashMap<>();
                boneMorphs.put(bone, map);
            }
            for (BoneMorph boneMorph : set) {
                if (map.containsKey(boneMorph)) continue;
                map.put(boneMorph, new Float[]{0F, 1F});
            }
        }
        for (Map.Entry<Material, Set<MaterialMorph>> entry : morph.getMaterialMorphs().entrySet()) {
            Material material = entry.getKey();
            Set<MaterialMorph> set = entry.getValue();
            Map<MaterialMorph, Float[]> map;
            if (materialMorphs.containsKey(material)) {
                map = materialMorphs.get(material);
            } else {
                map = new HashMap<>();
                materialMorphs.put(material, map);
            }
            for (MaterialMorph materialMorph : set) {
                if (map.containsKey(materialMorph)) continue;
                map.put(materialMorph, new Float[]{0F, 1F});
            }
        }
    }

    public boolean activateMorph(IdType id, float value) {
        return activateMorph(id, value, 1F);
    }

    public boolean activateMorph(IdType id, float value, float factor) {
        MorphType morph = this.morph.getMorph(id);
        if (morph == null) return false;
        final float v = Math.clamp(value, 0.0f, 1.0f);
        if (morph instanceof GroupMorph<?> group) {
            return group.getMorphs().stream()
                    .map(m -> this.innerActivateMorph(m, v, factor))
                    .reduce(Boolean::logicalOr).orElse(false);
        }
        return innerActivateMorph(morph, v, factor);
    }

    protected boolean innerActivateMorph(MorphType morph, float value, float factor) {
        if (morph instanceof GroupMorph<?> g) {
            return g.getMorphs().stream()
                    .map(m -> this.innerActivateMorph(m, value, g.getFactors().get(m) * factor))
                    .reduce(Boolean::logicalOr)
                    .orElse(false);
        } else if (morph instanceof VertexMorph<?> v) {
            Vertex vertex = v.getOriginal();
            if (!vertexMorphs.containsKey(vertex)) return false;
            if (!vertexMorphs.get(vertex).containsKey(morph)) return false;
            vertexMorphs.get(vertex).put(v, new Float[]{value, factor});
            dirtyVertices.add(vertex);
            return true;
        } else if (morph instanceof MeshMorph m) {
            Mesh mesh = m.getOriginal();
            if (!meshMorphs.containsKey(mesh)) return false;
            if (!meshMorphs.get(mesh).containsKey(morph)) return false;
            meshMorphs.get(mesh).put(m, new Float[]{value, factor});
            dirtyMeshes.add(mesh);
            return true;
        } else if (morph instanceof BoneMorph b) {
            Bone bone = b.getOriginal();
            if (!boneMorphs.containsKey(bone)) return false;
            if (!boneMorphs.get(bone).containsKey(morph)) return false;
            boneMorphs.get(bone).put(b, new Float[]{value, factor});
            dirtyBones.add(bone);
            return true;
        } else if (morph instanceof MaterialMorph m) {
            Material material = m.getOriginal();
            if (!materialMorphs.containsKey(material)) return false;
            if (!materialMorphs.get(material).containsKey(morph)) return false;
            materialMorphs.get(material).put(m, new Float[]{value, factor});
            dirtyMaterials.add(material);
            return true;
        }
        return false;
    }

    public void deactivateMorph(IdType id) {
        deactivateMorph(id, 1F);
    }

    public void deactivateMorph(IdType id, float factor) {
        activateMorph(id, 0F, factor);
    }

    public Vertex getVertex(Vertex original) {
        return vertexCache.getOrDefault(original, original);
    }

    public Mesh getMesh(Mesh original) {
        return meshCache.getOrDefault(original, original);
    }

    public Bone getBone(Bone original) {
        return boneCache.getOrDefault(original, original);
    }

    public Material getMaterial(Material original) {
        return materialCache.getOrDefault(original, original);
    }

    public void clearCaches() {
        vertexCache.clear();
        meshCache.clear();
        boneCache.clear();
        materialCache.clear();
    }

    public void update() {
        if (!isDirty()) return;
        if (isVerticesDirty()) {
            newlyUpdatedVertices.clear();
            List<Vertex> result = new ArrayList<>();
            Map<VertexMorph, Float[]> map;
            for (Vertex v : dirtyVertices) {
                map = vertexMorphs.get(v);
                for (Map.Entry<VertexMorph, Float[]> entry : map.entrySet()) {
                    VertexMorph vertexMorph = entry.getKey();
                    Float[] value = entry.getValue();
                    result.add(vertexMorph.morph(v, value[0], value[1]));
                }
                BlenderType<Vertex> blender = getBlender(v, map, result);
                if (blender != null) {
                    Vertex vx = blender.blend(v, map, result);
                    vertexCache.put(v, vx);
                    newlyUpdatedVertices.put(v, vx);
                }
            }
        }
        if (isBonesDirty()) {
            newlyUpdatedBones.clear();
            List<Bone> result = new ArrayList<>();
            Map<BoneMorph, Float[]> map;
            for (Bone v : dirtyBones) {
                map = boneMorphs.get(v);
                for (Map.Entry<BoneMorph, Float[]> entry : map.entrySet()) {
                    BoneMorph boneMorph = entry.getKey();
                    Float[] value = entry.getValue();
                    result.add(boneMorph.morph(v, value[0], value[1]));
                }
                BlenderType<Bone> blender = getBlender(v, map, result);
                if (blender != null) {
                    Bone b =blender.blend(v, map, result);
                    boneCache.put(v, b);
                    newlyUpdatedBones.put(v, b);
                }
            }
        }
        if (isMeshesDirty()) {
            newlyUpdatedMeshes.clear();
            List<Mesh> result = new ArrayList<>();
            Map<MeshMorph, Float[]> map;
            for (Mesh v : dirtyMeshes) {
                map = meshMorphs.get(v);
                for (Map.Entry<MeshMorph, Float[]> entry : map.entrySet()) {
                    MeshMorph meshMorph = entry.getKey();
                    Float[] value = entry.getValue();
                    result.add(meshMorph.morph(v, value[0], value[1]));
                }
                BlenderType<Mesh> blender = getBlender(v, map, result);
                if (blender != null) {
                    Mesh m = blender.blend(v, map, result);
                    meshCache.put(v, m);
                    newlyUpdatedMeshes.put(v, m);
                }
            }
        }
        if (isMaterialsDirty()) {
            newlyUpdatedMaterials.clear();
            List<Material> result = new ArrayList<>();
            Map<MaterialMorph, Float[]> map;
            for (Material v : dirtyMaterials) {
                map = materialMorphs.get(v);
                for (Map.Entry<MaterialMorph, Float[]> entry : map.entrySet()) {
                    MaterialMorph materialMorph = entry.getKey();
                    Float[] value = entry.getValue();
                    result.add(materialMorph.morph(v, value[0], value[1]));
                }
                BlenderType<Material> blender = getBlender(v, map, result);
                if (blender != null) {
                    Material m = blender.blend(v, map, result);
                    materialCache.put(v, m);
                    newlyUpdatedMaterials.put(v, m);
                }
            }
        }
        clearAllDirtyMarks();
        collectMorphedVertices(resultMappingType);
    }

    public <T> @Nullable BlenderType<T> getBlender(T original,
                                                   Map<? extends MorphType, Float[]> map,
                                                   List<T> results) {
        return null;
    }

    public boolean isDirty() {
        return !(
                        dirtyVertices.isEmpty() &&
                        dirtyBones.isEmpty() &&
                        dirtyMeshes.isEmpty() &&
                        dirtyMaterials.isEmpty()
        );
    }

    public boolean isBonesDirty() {
        return !dirtyBones.isEmpty();
    }

    public boolean isMeshesDirty() {
        return !dirtyMeshes.isEmpty();
    }

    public boolean isMaterialsDirty() {
        return !dirtyMaterials.isEmpty();
    }

    public boolean isVerticesDirty() {
        return !dirtyVertices.isEmpty();
    }

    public void clearAllDirtyMarks() {
        dirtyMeshes.clear();
        dirtyVertices.clear();
        dirtyMaterials.clear();
        dirtyBones.clear();
    }

    public Map<Vertex, MorphResult<Vertex>> collectMorphedVertices(byte includes) {
        if (includes == 0) return newlyMorphedResults;
        boolean includeVertices = (includes & 8) > 0;
        boolean includeBones = (includes & 4) > 0;
        boolean includeMeshes = (includes & 2) > 0;
        boolean includeMaterials = (includes & 1) > 0;
        Map<Vertex, MorphResult<Vertex>> result = getVertexMorphResultMap(includeVertices);
        if (includeBones) {
            Model model = getMorph().getModel();
            for (Map.Entry<Bone, Bone> entry : newlyUpdatedBones.entrySet()) {
                Bone original = entry.getKey();
                Bone target = entry.getValue();
                Set<Vertex> vertexByBone = model.getVertexByBone(original);
                putDataIntoMorphVertices(vertexByBone, result, original, target);
            }
        }
        if (includeMeshes) {
            for (Map.Entry<Mesh, Mesh> entry : newlyUpdatedMeshes.entrySet()) {
                Mesh original = entry.getKey();
                Mesh target = entry.getValue();
                putDataIntoMorphVertices(List.of(original.getVertices()), result, original, target);
            }
        }
        if (includeMaterials) {
            Model model = getMorph().getModel();
            for (Map.Entry<Material, Material> entry : newlyUpdatedMaterials.entrySet()) {
                Material original = entry.getKey();
                Material target = entry.getValue();
                Set<Vertex> vertexByMaterials = model.getVertexByMaterial(original);
                putDataIntoMorphVertices(vertexByMaterials, result, original, target);
            }
        }
        return result;
    }

    private @NotNull Map<Vertex, MorphResult<Vertex>> getVertexMorphResultMap(boolean includeVertices) {
        Map<Vertex, MorphResult<Vertex>> result = newlyMorphedResults;
        result.clear();

        if (includeVertices) {
            for (Map.Entry<Vertex, Vertex> entry : newlyUpdatedVertices.entrySet()) {
                Vertex original = entry.getKey();
                Vertex target = entry.getValue();
                MorphResult mr;
                Map<Object, Object> orgAndTarget;
                if (result.containsKey(original)) {
                    mr = result.get(original);
                    orgAndTarget = mr.morphTargetAndResult();
                } else {
                    orgAndTarget = new HashMap<>();
                    mr = new MorphResult<>(original, orgAndTarget);
                    result.put(original, mr);
                }
                orgAndTarget.put(original, target);
            }
        }
        return result;
    }

    protected void putDataIntoMorphVertices(Collection<Vertex> vertices,
                                            Map<Vertex, MorphResult<Vertex>> resltMap,
                                            Object original, Object target) {
        for (Vertex v : vertices) {
            MorphResult mr;
            Map<Object, Object> orgAndTarget;
            if (resltMap.containsKey(v)) {
                mr = resltMap.get(v);
                orgAndTarget = mr.morphTargetAndResult();
            } else {
                orgAndTarget = new HashMap<>();
                mr = new MorphResult<>(v, orgAndTarget);
                resltMap.put(v, mr);
            }
            orgAndTarget.put(original, target);
        }
    }
}
