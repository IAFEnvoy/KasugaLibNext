package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.models.uml.dynamic.math.Lerp;
import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.List;

/**
 * Pure-function keyframe sampler for {@link AnimationClip}. No Minecraft / model dependency — the
 * sampled {@link Pose} is consumed by a {@code PoseDriver} (e.g. {@link AnimationClipPoseDriver}) and
 * flushed through the existing pose sink.
 *
 * <p>Playback is looping: {@code time} wraps at {@code clip.durationSeconds()}. Bones and morphs
 * interpolate between keyframes (time axis shaped by the segment's {@link Easing}); material frames
 * snap to the nearer keyframe.
 */
public final class AnimationSampler {

    private AnimationSampler() {
    }

    public static Pose sample(AnimationClip clip, float time) {
        float t = loopTime(clip, time);
        Pose.Builder builder = new Pose.Builder();
        for (AnimationClip.BoneTrack track : clip.bones()) {
            Transform transform = sampleBone(track.keyframes(), t);
            if (transform != null) {
                builder.bone(track.bone(), transform, ApplyMode.REPLACE);
            }
        }
        for (AnimationClip.MorphTrack track : clip.morphs()) {
            builder.morph(track.morph(), sampleMorph(track.keyframes(), t), 1f);
        }
        for (AnimationClip.FrameTrack track : clip.frames()) {
            builder.frame(track.material(), sampleFrame(track.keyframes(), t));
        }
        return builder.build();
    }

    private static float loopTime(AnimationClip clip, float time) {
        float duration = clip.durationSeconds();
        if (duration <= 0f || time < 0f) {
            return 0f;
        }
        return time % duration;
    }

    private static Transform sampleBone(List<AnimationClip.Keyframe> keyframes, float t) {
        if (keyframes.isEmpty()) {
            return null;
        }
        if (t <= keyframes.get(0).time()) {
            return toTransform(keyframes.get(0).transform());
        }
        AnimationClip.Keyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return toTransform(last.transform());
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            AnimationClip.Keyframe from = keyframes.get(i);
            AnimationClip.Keyframe to = keyframes.get(i + 1);
            if (t >= from.time() && t < to.time()) {
                float span = to.time() - from.time();
                float u = span <= 0f ? 1f : (t - from.time()) / span;
                Easing easing = from.easing() != null ? from.easing() : Easing.linear();
                float eased = easing.apply(u);
                return Lerp.lerpInto(toTransform(from.transform()), toTransform(to.transform()), eased, new Transform());
            }
        }
        return toTransform(last.transform());
    }

    private static float sampleMorph(List<AnimationClip.MorphKeyframe> keyframes, float t) {
        if (keyframes.isEmpty()) {
            return 0f;
        }
        if (t <= keyframes.get(0).time()) {
            return keyframes.get(0).value();
        }
        AnimationClip.MorphKeyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return last.value();
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            AnimationClip.MorphKeyframe from = keyframes.get(i);
            AnimationClip.MorphKeyframe to = keyframes.get(i + 1);
            if (t >= from.time() && t < to.time()) {
                float span = to.time() - from.time();
                float u = span <= 0f ? 1f : (t - from.time()) / span;
                Easing easing = from.easing() != null ? from.easing() : Easing.linear();
                return Lerp.lerp(from.value(), to.value(), easing.apply(u));
            }
        }
        return last.value();
    }

    private static int sampleFrame(List<AnimationClip.FrameKeyframe> keyframes, float t) {
        if (keyframes.isEmpty()) {
            return 0;
        }
        AnimationClip.FrameKeyframe first = keyframes.get(0);
        if (t <= first.time()) {
            return first.frame();
        }
        AnimationClip.FrameKeyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return last.frame();
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            AnimationClip.FrameKeyframe from = keyframes.get(i);
            AnimationClip.FrameKeyframe to = keyframes.get(i + 1);
            if (t >= from.time() && t < to.time()) {
                float span = to.time() - from.time();
                float u = span <= 0f ? 1f : (t - from.time()) / span;
                return u < 0.5f ? from.frame() : to.frame();
            }
        }
        return last.frame();
    }

    /** Mirrors {@code DefinitionStateMachineFactory.buildTransform}: translate → rotate(degrees) → scale. */
    public static Transform toTransform(TransformDefinition def) {
        Transform transform = new Transform();
        transform.translate(def.translate().x, def.translate().y, def.translate().z);
        transform.rotate(def.rotate().x, def.rotate().y, def.rotate().z, true);
        transform.scale(def.scale().x, def.scale().y, def.scale().z);
        return transform;
    }
}