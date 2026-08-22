package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import org.jetbrains.annotations.Nullable;

/** Read/write context supplied to one stage of the model pose pipeline. */
public record PoseEvaluationContext(ModelInstance model, SkeletonInstance skeleton,
                                    @Nullable MmdRagdoll physics,
                                    PoseEffector.Stage stage, float deltaSeconds) {
}
