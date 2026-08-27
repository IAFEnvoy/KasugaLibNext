package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lombok.Getter;

public abstract class VertexMorph<IdType> implements MorphType<Vertex, IdType> {

    @Getter
    protected final Vertex vertex;

    public VertexMorph(Vertex vertex) {
        this.vertex = vertex;
    }

    @Override
    public boolean isValidMorphInput(Vertex input, float percentage, float factor) {
        return true;
    }

    @Override
    public Vertex morph(Vertex input, float percentage, float factor) {
        return input;
    }

    @Override
    public Vertex getOriginal() {
        return vertex;
    }
}
