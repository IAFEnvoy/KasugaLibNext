package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion.*;

import java.util.Map;

/** Fully sampled VMD state at one (possibly fractional) frame. */
public record VmdPose(
        double frame,
        Map<String, Transform> bones,
        Map<String, Float> morphs,
        CameraKeyframe camera,
        LightKeyframe light,
        ShadowKeyframe shadow,
        PropertyKeyframe properties
) {
    public VmdPose {
        bones = Map.copyOf(bones);
        morphs = Map.copyOf(morphs);
    }
}
