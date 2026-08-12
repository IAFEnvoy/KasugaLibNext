package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;

import java.util.Objects;

public final class VpdPlayer {
    private final VpdPose pose;
    private final Vector3f translationScale;

    public VpdPlayer(VpdPose pose) { this(pose, new Vector3f(1)); }

    public VpdPlayer(VpdPose pose, Vector3f translationScale) {
        this.pose = Objects.requireNonNull(pose, "pose");
        this.translationScale = new Vector3f(translationScale);
    }

    public void apply(ModelInstance instance) {
        pose.bones().forEach((name, source) -> {
            Transform scaled = new Transform().translate(new Vector3f(source.getPosition()).mul(translationScale))
                    .mul(source.getRotation());
            instance.getSkeletonInstance().transform(name, scaled);
        });
        pose.morphs().forEach((name, weight) -> instance.getMorph().activateMorph(name, weight));
        instance.updateImmediate();
    }
}
