package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.material.Material;

public abstract class MaterialMorph<IdType> implements MorphType<Material, IdType> {

    protected final Material material;

    public MaterialMorph(Material material) {
        this.material = material;
    }


    @Override
    public boolean isValidMorphInput(Material input, float percentage, float factor) {
        return true;
    }

    @Override
    public Material morph(Material input, float percentage, float factor) {
        return input;
    }

    @Override
    public Material getOriginal() {
        return material;
    }
}
