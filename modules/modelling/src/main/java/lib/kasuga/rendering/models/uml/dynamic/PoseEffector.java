package lib.kasuga.rendering.models.uml.dynamic;

/**
 * Procedural pose/IK/physics hook evaluated in a deterministic model pipeline.
 *
 * <p>{@link Stage#BEFORE_IK} may write animation-local transforms or transient
 * IK targets. {@link Stage#AFTER_IK} reads the solved animation target and may
 * update native physics targets. {@link Stage#AFTER_PHYSICS} observes the final
 * pose after Box3D writeback.</p>
 */
@FunctionalInterface
public interface PoseEffector {
    enum Stage { BEFORE_IK, AFTER_IK, AFTER_PHYSICS }

    void apply(PoseEvaluationContext context);
}
