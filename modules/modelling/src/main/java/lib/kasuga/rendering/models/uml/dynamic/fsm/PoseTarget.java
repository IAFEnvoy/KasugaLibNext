package lib.kasuga.rendering.models.uml.dynamic.fsm;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable snapshot of a {@link StateMachine}'s per-layer pose state at one game tick, published by the main
 * thread (under a {@code volatile} reference) for the render thread to sample. Carries only the runtime-mutable
 * bits the render thread needs to (re)compose the pose — the active state, the in-flight transition and its
 * elapsed seconds — plus each layer's static blend props. {@link State} / {@link Transition} pose data is
 * immutable after build, so the render thread can call {@code buildPose()} on the referenced states safely.
 *
 * <p>This is the thread handoff: the main thread owns every {@link Layer} mutation ({@code active},
 * {@code activeTransition}, {@code transitionElapsed}); the render thread never reads those fields directly,
 * only this snapshot. Built once per game tick, read many times per frame.
 */
public record PoseTarget(
        List<LayerTarget> layers,
        List<BlendMode> modes,
        List<Float> weights,
        List<BoneMask> masks
) {
    public PoseTarget {
        layers = List.copyOf(layers);
        modes = List.copyOf(modes);
        weights = List.copyOf(weights);
        masks = List.copyOf(masks);
    }

    /** One layer's pose target: the active state, the in-flight cross-fade (if any), and its elapsed seconds. */
    public record LayerTarget(
            @Nullable State<?> activeState,
            @Nullable Transition<?> activeTransition,
            float transitionElapsed
    ) {
    }
}
