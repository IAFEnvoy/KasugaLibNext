package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;

public abstract class MeshMorph<IdType> implements MorphType<Mesh, IdType> {

    protected final Mesh mesh;

    public MeshMorph(Mesh mesh) {
        this.mesh = mesh;
    }


    @Override
    public boolean isValidMorphInput(Mesh input, float percentage, float factor) {
        return true;
    }

    @Override
    public Mesh morph(Mesh input, float percentage, float factor) {
        return input;
    }

    @Override
    public Mesh getOriginal() {
        return null;
    }
}
