package lib.kasuga.rendering.models.mc.backend.schedule;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Central render-scheduling state for mounted model instances.
 *
 * <p>Vanilla integrates its own culling with the renderer mechanism: an
 * {@code Entity} beyond tracking range or a {@code BlockEntity} outside the
 * frustum never reaches its renderer's {@code render()} method. Adapters
 * (see {@link UmlBlockEntityRenderer} and
 * {@code UmlModelEntityRenderer}) forward that decision here by marking the
 * instance during the vanilla pass; the global UML pipeline — which runs
 * later in the same frame at the AFTER_ENTITIES phase — then consumes the
 * mark and skips sampling and drawing entirely.</p>
 *
 * <p>All methods are safe to call from both the client tick thread and the
 * render thread; the internal maps are swapped atomically on read.</p>
 */
public final class ModelRenderScheduler {
    private static final Object LOCK = new Object();
    private static Map<ModelInstance, Policy> policies = new IdentityHashMap<>();
    private static Set<ModelInstance> markedThisFrame = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Double buffer so the render thread reads a stable snapshot of marks. */
    private static Set<ModelInstance> consumedMarks = Collections.newSetFromMap(new IdentityHashMap<>());

    private ModelRenderScheduler() {}

    /** Selects who controls visibility for this instance. */
    public static void setMode(ModelInstance instance, RenderScheduleMode mode) {
        synchronized (LOCK) {
            if (mode == RenderScheduleMode.ALWAYS) {
                policies.remove(instance);
                return;
            }
            policies.computeIfAbsent(instance, ignored -> new Policy()).mode = mode;
        }
    }

    public static RenderScheduleMode mode(ModelInstance instance) {
        synchronized (LOCK) {
            Policy policy = policies.get(instance);
            return policy == null ? RenderScheduleMode.ALWAYS : policy.mode;
        }
    }

    /** MANUAL mode only: hard show/hide switch. */
    public static void setVisible(ModelInstance instance, boolean visible) {
        Objects.requireNonNull(instance, "instance");
        synchronized (LOCK) {
            Policy policy = policies.computeIfAbsent(instance, ignored -> new Policy());
            policy.mode = RenderScheduleMode.MANUAL;
            policy.manualVisible = visible;
        }
    }

    /**
     * VANILLA_RENDERER mode only: called from inside a vanilla renderer's
     * {@code render()} — proof that vanilla passed its own culling this frame.
     */
    public static void markRenderedThisFrame(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance");
        synchronized (LOCK) {
            markedThisFrame.add(instance);
        }
    }

    /** Per-instance view-distance cap in blocks; zero disables distance culling. */
    public static void setMaxRenderDistance(ModelInstance instance, float blocks) {
        Objects.requireNonNull(instance, "instance");
        synchronized (LOCK) {
            if (!(blocks > 0f)) {
                Policy policy = policies.get(instance);
                if (policy != null) policy.maxRenderDistance = 0f;
                return;
            }
            policies.computeIfAbsent(instance, ignored -> new Policy()).maxRenderDistance = blocks;
        }
    }

    public static float maxRenderDistance(ModelInstance instance) {
        synchronized (LOCK) {
            Policy policy = policies.get(instance);
            return policy == null ? 0f : policy.maxRenderDistance;
        }
    }

    /**
     * Consumes the marks accumulated during the vanilla pass. Called once at
     * the global pipeline's frame start — the vanilla pass (entities, block
     * entities) has already run by then, so its decisions are preserved in the
     * consumed snapshot while the buffer clears for the next frame.
     */
    public static void flipFrame() {
        synchronized (LOCK) {
            Set<ModelInstance> swap = consumedMarks;
            consumedMarks = markedThisFrame;
            markedThisFrame = swap;
            markedThisFrame.clear();
        }
    }

    /** Whether a vanilla renderer marked the instance during this frame's pass. */
    public static boolean wasMarkedThisFrame(ModelInstance instance) {
        synchronized (LOCK) {
            return consumedMarks.contains(instance) || markedThisFrame.contains(instance);
        }
    }

    /** Combined per-frame decision for one instance. */
    public static boolean shouldRender(ModelInstance instance) {
        synchronized (LOCK) {
            Policy policy = policies.get(instance);
            if (policy == null) return true; // ALWAYS
            return switch (policy.mode) {
                case ALWAYS -> true;
                case MANUAL -> policy.manualVisible;
                case VANILLA_RENDERER -> consumedMarks.contains(instance)
                        || markedThisFrame.contains(instance);
            };
        }
    }

    /** Distance gate evaluated against the camera position. */
    public static boolean withinRenderDistance(ModelInstance instance, float cameraDistanceSquared) {
        float maximum;
        synchronized (LOCK) {
            Policy policy = policies.get(instance);
            maximum = policy == null ? 0f : policy.maxRenderDistance;
        }
        return !(maximum > 0f) || cameraDistanceSquared <= maximum * maximum;
    }

    /** Drops all scheduling state for an instance (called when it unmounts). */
    public static void detach(ModelInstance instance) {
        if (instance == null) return;
        synchronized (LOCK) {
            policies.remove(instance);
            markedThisFrame.remove(instance);
            consumedMarks.remove(instance);
        }
    }

    public static void resetAll() {
        synchronized (LOCK) {
            policies.clear();
            markedThisFrame.clear();
            consumedMarks.clear();
        }
    }

    @Nullable
    static Policy policyOf(ModelInstance instance) {
        synchronized (LOCK) {
            return policies.get(instance);
        }
    }

    private static final class Policy {
        private RenderScheduleMode mode = RenderScheduleMode.ALWAYS;
        private boolean manualVisible = true;
        private float maxRenderDistance;
    }
}
