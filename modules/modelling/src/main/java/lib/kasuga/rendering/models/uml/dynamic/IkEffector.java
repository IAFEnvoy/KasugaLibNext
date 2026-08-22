package lib.kasuga.rendering.models.uml.dynamic;

import org.joml.Vector3f;

import java.util.Objects;

/** A world-space position target for one PMX IK controller. */
public final class IkEffector implements PoseEffector {
    private final String controllerBone;
    private volatile Target target;

    public IkEffector(String controllerBone, Vector3f worldTarget) {
        this(controllerBone, worldTarget, 1f);
    }

    public IkEffector(String controllerBone, Vector3f worldTarget, float weight) {
        if (controllerBone == null || controllerBone.isBlank()) {
            throw new IllegalArgumentException("controller bone must not be blank");
        }
        this.controllerBone = controllerBone;
        setTarget(worldTarget, weight);
    }

    public String controllerBone() { return controllerBone; }
    public Vector3f target() { return new Vector3f(target.position); }
    public float weight() { return target.weight; }
    public boolean enabled() { return target.enabled; }

    public void setTarget(Vector3f worldTarget) { setTarget(worldTarget, weight()); }

    public void setTarget(Vector3f worldTarget, float weight) {
        Vector3f position = new Vector3f(Objects.requireNonNull(worldTarget, "worldTarget"));
        if (!position.isFinite() || !Float.isFinite(weight) || weight < 0f || weight > 1f) {
            throw new IllegalArgumentException("IK target must be finite and weight within [0, 1]");
        }
        Target previous = target;
        target = new Target(position, weight, previous == null || previous.enabled);
    }

    public void setEnabled(boolean enabled) {
        Target value = target;
        target = new Target(value.position, value.weight, enabled);
    }

    @Override
    public void apply(PoseEvaluationContext context) {
        if (context.stage() != Stage.BEFORE_IK) return;
        Target value = target;
        if (value.enabled) {
            context.skeleton().setFrameIkTarget(controllerBone, value.position, value.weight);
        }
    }

    private record Target(Vector3f position, float weight, boolean enabled) {
        private Target {
            position = new Vector3f(position);
        }
    }
}
