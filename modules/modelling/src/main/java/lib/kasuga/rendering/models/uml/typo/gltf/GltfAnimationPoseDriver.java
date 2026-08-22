package lib.kasuga.rendering.models.uml.typo.gltf;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-sampled glTF TRS animation player for a converted UML model. */
public final class GltfAnimationPoseDriver implements PoseDriver {
    private final ModelInstance instance;
    private final GltfModelData data;
    private final Map<String, GltfAsset.AnimationClip> clips = new HashMap<>();
    private volatile Playback playback = Playback.stopped();

    public GltfAnimationPoseDriver(ModelInstance instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
        if (!(instance.getModel().getModelData() instanceof GltfModelData gltf)) {
            throw new IllegalArgumentException("model was not created by GltfModelConverter");
        }
        this.data = gltf;
        for (GltfAsset.AnimationClip clip : gltf.asset().animations()) clips.put(clip.name(), clip);
    }

    public boolean hasClip(String name) { return clips.containsKey(name); }
    public String currentClip() { return playback.clip == null ? null : playback.clip.name(); }

    public boolean play(String name, boolean loop) {
        GltfAsset.AnimationClip clip = clips.get(name);
        if (clip == null) return false;
        playback = new Playback(clip, 0f, 0f, 1f, loop, true);
        return true;
    }

    public void stop() { playback = Playback.stopped(); }

    public void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0f) throw new IllegalArgumentException("speed must be finite and non-negative");
        Playback value = playback;
        playback = new Playback(value.clip, value.previousSeconds, value.seconds, speed, value.loop, value.playing);
    }

    @Override
    public void tick(float dt) {
        if (!Float.isFinite(dt) || dt < 0f) return;
        Playback value = playback;
        if (!value.playing || value.clip == null) return;
        float next = value.seconds + dt * value.speed;
        if (!value.loop && next >= value.clip.duration()) next = value.clip.duration();
        playback = new Playback(value.clip, value.seconds, next, value.speed,
                value.loop, value.loop || next < value.clip.duration());
    }

    @Override
    public void sample(float partialTick) {
        Playback value = playback;
        if (value.clip == null) return;
        float fraction = Math.clamp(partialTick, 0f, 1f);
        float elapsed = Math.fma(value.seconds - value.previousSeconds, fraction, value.previousSeconds);
        float duration = value.clip.duration();
        float time = duration <= 1e-7f ? value.clip.startTime()
                : value.clip.startTime() + (value.loop ? elapsed % duration : Math.min(elapsed, duration));
        apply(value.clip, time);
    }

    private void apply(GltfAsset.AnimationClip clip, float time) {
        GltfAsset.NodeHierarchy nodes = data.asset().nodes();
        Vector3f[] translations = java.util.Arrays.stream(nodes.translations())
                .map(Vector3f::new).toArray(Vector3f[]::new);
        Quaternionf[] rotations = java.util.Arrays.stream(nodes.rotations())
                .map(Quaternionf::new).toArray(Quaternionf[]::new);
        Vector3f[] scales = java.util.Arrays.stream(nodes.scales())
                .map(Vector3f::new).toArray(Vector3f[]::new);
        for (GltfAsset.AnimationTrack track : clip.tracks()) {
            if (track.nodeIndex() < 0 || track.nodeIndex() >= nodes.size() || track.times().length == 0) continue;
            int key = segment(track.times(), time);
            int next = Math.min(key + 1, track.times().length - 1);
            float frame = track.times()[next] - track.times()[key];
            float amount = next == key || frame <= 1e-8f ? 0f
                    : Math.clamp((time - track.times()[key]) / frame, 0f, 1f);
            switch (track.path()) {
                case TRANSLATION -> sampleVector(track, key, next, amount, translations[track.nodeIndex()]);
                case SCALE -> sampleVector(track, key, next, amount, scales[track.nodeIndex()]);
                case ROTATION -> sampleRotation(track, key, next, amount, rotations[track.nodeIndex()]);
            }
        }
        for (int node = 0; node < nodes.size(); node++) {
            Bone bone = data.boneByNode().get(node);
            if (bone == null) continue;
            Matrix4f bindLocal = new Matrix4f().translationRotateScale(nodes.translations()[node],
                    nodes.rotations()[node], nodes.scales()[node]);
            Matrix4f animatedLocal = new Matrix4f().translationRotateScale(
                    translations[node], rotations[node], scales[node]);
            Matrix4f delta = bindLocal.invert().mul(animatedLocal);
            instance.getSkeletonInstance().transform(bone, new Transform().set(delta));
        }
    }

    private static int segment(float[] times, float time) {
        int low = 0, high = times.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (times[mid] <= time) low = mid; else high = mid - 1;
        }
        return low;
    }

    private static void sampleVector(GltfAsset.AnimationTrack track, int key, int next,
                                     float amount, Vector3f target) {
        if (track.interpolation() == GltfAsset.Interpolation.CUBIC_SPLINE) {
            target.set(cubic(track, key, next, amount, 0), cubic(track, key, next, amount, 1),
                    cubic(track, key, next, amount, 2));
            return;
        }
        int first = key * 3, second = next * 3;
        float t = track.interpolation() == GltfAsset.Interpolation.STEP ? 0f : amount;
        target.set(mix(track.values()[first], track.values()[second], t),
                mix(track.values()[first + 1], track.values()[second + 1], t),
                mix(track.values()[first + 2], track.values()[second + 2], t));
    }

    private static void sampleRotation(GltfAsset.AnimationTrack track, int key, int next,
                                       float amount, Quaternionf target) {
        if (track.interpolation() == GltfAsset.Interpolation.CUBIC_SPLINE) {
            target.set(cubic(track, key, next, amount, 0), cubic(track, key, next, amount, 1),
                    cubic(track, key, next, amount, 2), cubic(track, key, next, amount, 3)).normalize();
            return;
        }
        int first = key * 4, second = next * 4;
        Quaternionf a = new Quaternionf(track.values()[first], track.values()[first + 1],
                track.values()[first + 2], track.values()[first + 3]).normalize();
        if (track.interpolation() == GltfAsset.Interpolation.STEP || key == next) {
            target.set(a);
        } else {
            Quaternionf b = new Quaternionf(track.values()[second], track.values()[second + 1],
                    track.values()[second + 2], track.values()[second + 3]).normalize();
            target.set(a).slerp(b, amount).normalize();
        }
    }

    private static float cubic(GltfAsset.AnimationTrack track, int key, int next,
                               float amount, int component) {
        int components = track.path().components();
        int value0 = (key * 3 + 1) * components + component;
        if (key == next) return track.values()[value0];
        int tangent0 = (key * 3 + 2) * components + component;
        int tangent1 = (next * 3) * components + component;
        int value1 = (next * 3 + 1) * components + component;
        float duration = Math.max(0f, track.times()[next] - track.times()[key]);
        float t2 = amount * amount, t3 = t2 * amount;
        return (2f * t3 - 3f * t2 + 1f) * track.values()[value0]
                + (t3 - 2f * t2 + amount) * duration * track.values()[tangent0]
                + (-2f * t3 + 3f * t2) * track.values()[value1]
                + (t3 - t2) * duration * track.values()[tangent1];
    }

    private static float mix(float a, float b, float t) { return Math.fma(b - a, t, a); }

    private record Playback(GltfAsset.AnimationClip clip, float previousSeconds, float seconds,
                            float speed, boolean loop, boolean playing) {
        private static Playback stopped() { return new Playback(null, 0f, 0f, 1f, false, false); }
    }
}
