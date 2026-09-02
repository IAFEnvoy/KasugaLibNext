package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BlendMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Blender;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BoneMask;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;

/**
 * {@link PoseDriver} that plays a looping {@link AnimationClip}, following the same dual-cadence
 * pattern as {@code FsmPoseDriver}:
 *
 * <ul>
 *   <li><b>{@link #tick(float)} — main thread, game tick.</b> Advances the playback clock and publishes
 *       a {@code volatile} {@link ClipSnapshot} (clip + elapsed seconds). The host drives this via
 *       {@link ModelInstance#animate(float)}.</li>
 *   <li><b>{@link #sample(float)} — render thread, per frame.</b> Reads the latest snapshot and samples
 *       the clip at {@code elapsed + partialTick·TICK_SECONDS} so animation interpolates at frame rate,
 *       then flushes the pose through the driver's own {@link ModelInstancePoseSink}.</li>
 * </ul>
 *
 * <p>The driver owns the sink (the model's state machine, if any, keeps its own); on resource-reload
 * rebind, {@link #rebind(ModelInstance)} swaps only the sink target — playback progress survives.
 */
public final class AnimationClipPoseDriver implements PoseDriver {

    /** One game tick in seconds; the partialTick fraction multiplies this to interpolate between ticks. */
    private static final float TICK_SECONDS = 1f / 20f;

    private final Blender blender = new Blender();
    private ModelInstance model;
    private volatile ModelInstancePoseSink sink;
    private volatile ClipSnapshot snapshot;

    public AnimationClipPoseDriver(ModelInstance model) {
        this.model = model;
        this.sink = new ModelInstancePoseSink(model);
    }

    public ModelInstance model() {
        return model;
    }

    /** Start (or restart) playback of the given clip from the beginning. */
    public void play(AnimationClip clip) {
        snapshot = new ClipSnapshot(clip, 0f);
    }

    /** Stop playback; subsequent {@link #sample(float)} calls are no-ops until {@link #play} again. */
    public void stop() {
        snapshot = null;
    }

    public boolean isPlaying() {
        return snapshot != null;
    }

    /** Main-thread game-tick advance: advance the playback clock, publish a fresh snapshot. */
    @Override
    public void tick(float dt) {
        ClipSnapshot current = snapshot;
        if (current == null) {
            return;
        }
        snapshot = new ClipSnapshot(current.clip, current.elapsedSeconds + dt);
    }

    /**
     * Render-thread per-frame sample: sample the clip at frame rate (interpolated by {@code partialTick})
     * and flush the pose through the sink. No-op until playback starts.
     */
    @Override
    public void sample(float partialTick) {
        ClipSnapshot current = snapshot;
        if (current == null) {
            return;
        }
        float time = current.elapsedSeconds + partialTick * TICK_SECONDS;
        Pose pose = AnimationSampler.sample(current.clip, time);
        blender.reset();
        blender.applyLayer(BlendMode.BASE, pose, 1f, BoneMask.all());
        sink.apply(blender);
    }

    /**
     * Re-target a fresh {@link ModelInstance} (resource reload / model rebind): install a new
     * {@link ModelInstancePoseSink} for {@code fresh}. Playback progress is unchanged.
     */
    public void rebind(ModelInstance fresh) {
        this.model = fresh;
        this.sink = new ModelInstancePoseSink(fresh);
    }

    /** Thread handoff: the main thread writes it (volatile), the render thread reads it. */
    private record ClipSnapshot(AnimationClip clip, float elapsedSeconds) {
    }
}