package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link PoseDriver} backed by a {@link StateMachine}, driven at two cadences:
 *
 * <ul>
 *   <li><b>{@link #tick(float)} — main thread, game tick.</b> Advances the machine (a client {@code puppet} is
 *       built with a {@code null} sink so this advances cross-fade timers without flushing) and publishes a fresh
 *       {@link PoseTarget} snapshot (a {@code volatile} immutable record). The machine's mutable state stays
 *       main-thread-owned.</li>
 *   <li><b>{@link #sample(float)} — render thread, per frame.</b> Reads the latest {@link PoseTarget} and composes
 *       the pose at frame rate: cross-fades interpolate by {@code partialTick} (the fraction between game ticks),
 *       so animation is smooth at render rate instead of stepping at 20&nbsp;Hz. The pose is flushed through the
 *       driver's own {@link ModelInstancePoseSink}; the backend uploads to the GPU afterwards.</li>
 * </ul>
 *
 * <p>The driver owns the {@link ModelInstancePoseSink} (the machine's sink stays {@code null}); on resource-reload
 * rebind, {@link #rebind(ModelInstance)} swaps only the sink target — the machine and in-flight state survive.
 */
public final class FsmPoseDriver implements PoseDriver {

    /** One game tick in seconds; the partialTick fraction multiplies this to interpolate between ticks. */
    private static final float TICK_SECONDS = 1f / 20f;

    private final StateMachine<?> machine;
    private ModelInstance model;
    private volatile ModelInstancePoseSink sink;
    private final Blender blender = new Blender();
    private volatile PoseTarget target;

    public FsmPoseDriver(StateMachine<?> machine, ModelInstance model) {
        this.machine = machine;
        this.model = model;
        this.sink = new ModelInstancePoseSink(model);
    }

    public StateMachine<?> machine() {
        return machine;
    }

    public ModelInstance model() {
        return model;
    }

    /** The latest published {@link PoseTarget} (volatile read; {@code null} until the first {@link #tick}). */
    PoseTarget currentTarget() {
        return target;
    }

    /**
     * Main-thread game-tick advance: advance the machine (puppet {@code sink=null} → timers advance, no flush),
     * then publish a {@link PoseTarget} snapshot copied from {@link StateMachine#layers()}.
     */
    @Override
    public void tick(float dt) {
        machine.tick(dt);
        publishTarget();
    }

    private void publishTarget() {
        List<? extends Layer<?>> layers = machine.layers();
        List<PoseTarget.LayerTarget> layerTargets = new ArrayList<>(layers.size());
        List<BlendMode> modes = new ArrayList<>(layers.size());
        List<Float> weights = new ArrayList<>(layers.size());
        List<BoneMask> masks = new ArrayList<>(layers.size());
        for (Layer<?> layer : layers) {
            layerTargets.add(new PoseTarget.LayerTarget(
                    layer.active(), layer.activeTransition(), layer.transitionElapsed()));
            modes.add(layer.mode());
            weights.add(layer.weight());
            masks.add(layer.boneMask());
        }
        target = new PoseTarget(layerTargets, modes, weights, masks);
    }

    /**
     * Render-thread per-frame sample: compose the pose from the latest {@link PoseTarget} — cross-fades blend at
     * {@code alpha = (elapsed + partialTick·TICK_SECONDS) / crossFadeSeconds} — and flush via the sink. No-op until
     * the first {@link #tick(float)} publishes a target.
     */
    @Override
    public void sample(float partialTick) {
        PoseTarget snapshot = target;
        if (snapshot == null) {
            return;
        }
        compose(blender, snapshot, partialTick);
        sink.apply(blender);
    }

    /**
     * Compose the {@link PoseTarget} into {@code blender} at frame rate — cross-fades blend by {@code partialTick}.
     * Package-private so the same path {@link #sample} uses is unit-testable without a real {@link ModelInstance}
     * sink (assert on the blender's accumulators). Resets the blender first.
     */
    void compose(Blender blender, PoseTarget snapshot, float partialTick) {
        blender.reset();
        List<PoseTarget.LayerTarget> layerTargets = snapshot.layers();
        for (int i = 0; i < layerTargets.size(); i++) {
            PoseTarget.LayerTarget layerTarget = layerTargets.get(i);
            State<?> active = layerTarget.activeState();
            Transition<?> transition = layerTarget.activeTransition();
            Pose pose;
            if (active == null) {
                pose = Pose.empty();
            } else if (transition != null) {
                float crossFade = transition.crossFadeSeconds();
                float alpha = crossFade <= 0f
                        ? 1f
                        : Math.min(1f, (layerTarget.transitionElapsed() + partialTick * TICK_SECONDS) / crossFade);
                pose = PoseBlend.blend(active.buildPose(), transition.to().buildPose(), alpha);
            } else {
                pose = active.buildPose();
            }
            blender.applyLayer(snapshot.modes().get(i), pose, snapshot.weights().get(i), snapshot.masks().get(i));
        }
    }

    /**
     * Re-target a fresh {@link ModelInstance} (resource reload / model rebind): install a new
     * {@link ModelInstancePoseSink} for {@code fresh}. The machine, its layers, in-flight cross-fades and the
     * published {@link PoseTarget} are unchanged — only the destination of the pose flush changes.
     */
    public void rebind(ModelInstance fresh) {
        this.model = fresh;
        this.sink = new ModelInstancePoseSink(fresh);
    }
}
