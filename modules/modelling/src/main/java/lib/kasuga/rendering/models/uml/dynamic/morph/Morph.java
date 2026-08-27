package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.blender.BlenderType;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;

import java.util.*;

@Getter
public class Morph<IdType> {

    protected final Model model;

    protected final Map<IdType, MorphType> morphs;

    protected final Map<IdType, GroupMorph> groupMorphs;

    protected final Map<Vertex, Set<VertexMorph>> vertexMorphs;

    protected final Map<Bone, Set<BoneMorph>> boneMorphs;

    protected final Map<Material, Set<MaterialMorph>> materialMorphs;

    protected final Map<Mesh, Set<MeshMorph>> meshMorphs;

    protected final List<BlenderType<Vertex>> vertexBlenders;

    protected final List<BlenderType<Bone>> boneBlenders;

    protected final List<BlenderType<Material>> materialBlenders;

    protected final List<BlenderType<Mesh>> meshBlenders;

    public Morph(Model model) {
        this.model = model;
        morphs = new HashMap<>();
        groupMorphs = new HashMap<>();
        vertexMorphs = new HashMap<>();
        boneMorphs = new HashMap<>();
        materialMorphs = new HashMap<>();
        meshMorphs = new HashMap<>();
        vertexBlenders = new ArrayList<>();
        boneBlenders = new ArrayList<>();
        materialBlenders = new ArrayList<>();
        meshBlenders = new ArrayList<>();
    }

    public boolean addMorph(MorphType morph) {
        IdType id = (IdType) morph.getIdentifier();
        if (morphs.containsKey(id)) return false;
        morphs.put(id, morph);
        switch (morph) {
            case GroupMorph<?> g -> {
                groupMorphs.put(id, g);
                return g.getMorphs().stream()
                        .map(this::addMorph)
                        .reduce(Boolean::logicalOr)
                        .orElse(false);
            }
            case VertexMorph<?> v -> {
                vertexMorphs.computeIfAbsent(v.getOriginal(), vert -> new HashSet<>())
                        .add(v);
            }
            case BoneMorph b -> {
                boneMorphs.computeIfAbsent(b.getOriginal(), bt -> new HashSet<>())
                        .add(b);
            }
            case MaterialMorph m -> {
                materialMorphs.computeIfAbsent(m.getOriginal(), mt -> new HashSet<>())
                        .add(m);
            }
            case MeshMorph m -> {
                meshMorphs.computeIfAbsent(m.getOriginal(), mt -> new HashSet<>())
                        .add(m);
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    public MorphType getMorph(IdType id) {
        return morphs.get(id);
    }

    public boolean hasMorph(IdType id) {
        return morphs.containsKey(id);
    }

    public boolean hasGroupMorph(IdType id) {
        return groupMorphs.containsKey(id);
    }

    public int morphSize() {
        return morphs.size();
    }

    public int vertexMorphSize() {
        return vertexMorphs.size();
    }

    public int boneMorphSize() {
        return boneMorphs.size();
    }

    public int materialMorphSize() {
        return materialMorphs.size();
    }

    public int meshMorphSize() {
        return meshMorphs.size();
    }

    public int groupMorphSize() {
        return groupMorphs.size();
    }
}
