package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;

public abstract class BoneMorph<IdType> implements MorphType<Bone, IdType> {

    protected final Bone bone;

    public BoneMorph(Bone bone){
        this.bone = bone;
    }

    @Override
    public boolean isValidMorphInput(Bone input, float percentage, float factor) {
        return true;
    }

    @Override
    public Bone morph(Bone input, float percentage, float factor) {
        return input;
    }

    @Override
    public Bone getOriginal() {
        return bone;
    }
}
