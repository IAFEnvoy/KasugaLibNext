package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class GroupMorph<IdType> implements MorphType<List, IdType> {

    @Getter
    protected final List<MorphType> morphs;

    @Getter
    protected final Map<MorphType, Float> factors;

    public GroupMorph(List<MorphType> morphs, Map<MorphType, Float> factors) {
        this.morphs = morphs;
        this.factors = factors;
    }

    @Override
    public boolean isValidMorphInput(List morphList, float percentage, float factor) {
        return true;
    }

    @Override
    public List morph(List morphList, float percentage, float factor) {
        return new ArrayList<>();
    }

    @Override
    public List getOriginal() {
        return morphs.stream().map(MorphType::getOriginal).toList();
    }
}
