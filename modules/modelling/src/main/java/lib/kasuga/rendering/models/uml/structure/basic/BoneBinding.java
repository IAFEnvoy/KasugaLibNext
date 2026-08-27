package lib.kasuga.rendering.models.uml.structure.basic;

import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

@Getter
public class BoneBinding {

    private final Pair<Bone, Float>[] weights;

    @Nullable
    private final BoneBindingData data;

    @Getter
    @NonNull
    private final BoneBindingFunc func;

    public BoneBinding(Pair<Bone, Float>[] weights, BoneBindingFunc func, BoneBindingData data) {
        this.weights = weights;
        this.data = data;
        this.func = func;
        if (weights.length < 1) return;
        float w = 0;
        for (Pair<Bone, Float> weight : weights) {
            w += weight.getSecond();
        }
        if (w != 1) {
            for (int i = 0; i < weights.length; i++) {
                Pair<Bone, Float> weight = weights[i];
                weights[i] = Pair.of(weight.getFirst(), weight.getSecond() / w);
            }
        }
    }
}
