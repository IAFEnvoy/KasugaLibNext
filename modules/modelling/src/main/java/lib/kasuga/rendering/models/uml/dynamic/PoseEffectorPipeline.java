package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ordered, named effectors shared by animation, IK and Box3D evaluation. */
public final class PoseEffectorPipeline {
    private final Map<String, PoseEffector> effectors = new LinkedHashMap<>();

    public synchronized void add(String id, PoseEffector effector) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("effector id must not be blank");
        effectors.put(id, Objects.requireNonNull(effector, "effector"));
    }

    public synchronized boolean remove(String id) {
        return effectors.remove(id) != null;
    }

    public synchronized void clear() { effectors.clear(); }
    public synchronized boolean isEmpty() { return effectors.isEmpty(); }
    public synchronized List<String> ids() { return List.copyOf(effectors.keySet()); }

    public void evaluate(ModelInstance model, @Nullable MmdRagdoll physics,
                         PoseEffector.Stage stage, float deltaSeconds) {
        List<PoseEffector> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(effectors.values());
        }
        PoseEvaluationContext context = new PoseEvaluationContext(model,
                model.getSkeletonInstance(), physics, stage, deltaSeconds);
        for (PoseEffector effector : snapshot) effector.apply(context);
    }
}
