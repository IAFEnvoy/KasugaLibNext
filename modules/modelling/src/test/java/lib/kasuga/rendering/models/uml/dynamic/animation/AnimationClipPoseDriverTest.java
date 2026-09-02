package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AnimationClipPoseDriver dual-cadence playback tests. */
class AnimationClipPoseDriverTest {

    private static final float EPS = 1e-3f;

    private static AnimationClip wheelClip() {
        // 0°→180° over 1s (360° would be identity quaternion and interpolate to 0 everywhere)
        return new AnimationClip(Id.parse("kasuga_lib:wheel"), 1f,
                List.of(new AnimationClip.BoneTrack("root",
                        List.of(
                                new AnimationClip.Keyframe(0f,
                                        new TransformDefinition(new Vector3f(), new Vector3f(0f, 0f, 0f), new Vector3f(1f, 1f, 1f)),
                                        null),
                                new AnimationClip.Keyframe(1f,
                                        new TransformDefinition(new Vector3f(), new Vector3f(0f, 180f, 0f), new Vector3f(1f, 1f, 1f)),
                                        null)))),
                List.of(), List.of());
    }

    private static AnimationClipPoseDriver newDriver() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        AnimationClipPoseDriver driver = new AnimationClipPoseDriver(instance);
        instance.setPoseDriver(driver);
        return driver;
    }

    private static double rootAngle(AnimationClipPoseDriver driver) {
        Transform bone = driver.model().getSkeletonInstance().getTransforms().values().iterator().next();
        return Math.toDegrees(bone.getRotation().angle());
    }

    @Test
    void noopUntilPlay() {
        AnimationClipPoseDriver driver = newDriver();
        assertFalse(driver.isPlaying());
        driver.tick(1f / 20f);
        driver.sample(0f); // should not throw
    }

    @Test
    void playStartsPlaybackAndTickAdvances() {
        AnimationClipPoseDriver driver = newDriver();
        driver.play(wheelClip());
        assertTrue(driver.isPlaying());
        driver.tick(1f / 20f);
        driver.sample(0f);
        // 0.05s into a 1s clip rotating 0→180° linearly → 9°
        assertEquals(9.0, rootAngle(driver), 1.0);
    }

    @Test
    void partialTickInterpolatesBetweenTicks() {
        AnimationClipPoseDriver driver = newDriver();
        driver.play(wheelClip());
        driver.tick(0.05f); // elapsed = 0.05
        driver.sample(0.5f); // time = 0.05 + 0.5 * 0.05 = 0.075 → 13.5°
        assertEquals(13.5, rootAngle(driver), 1.0);
    }

    @Test
    void stopHaltsPlayback() {
        AnimationClipPoseDriver driver = newDriver();
        driver.play(wheelClip());
        driver.stop();
        assertFalse(driver.isPlaying());
        driver.tick(1f / 20f);
        driver.sample(0f); // should not throw
    }

    @Test
    void rebindSwapsSinkButKeepsProgress() {
        AnimationClipPoseDriver driver = newDriver();
        driver.play(wheelClip());
        driver.tick(1f / 20f); // elapsed 0.05
        driver.rebind(ModelInstanceFixture.minimal());
        driver.sample(0f);
        assertEquals(9.0, rootAngle(driver), 1.0);
    }

    @Test
    void codecRoundTrip() {
        AnimationClip original = wheelClip();
        AnimationClip decoded = AnimationClip.CODEC.decode(JsonOps.INSTANCE,
                AnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, original).result().orElseThrow())
                .resultOrPartial(error -> {
                    throw new AssertionError(error);
                }).orElseThrow().getFirst();
        assertEquals(original, decoded);
    }

    @Test
    void codecDecodesFromJson() {
        com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString("""
                {
                  "id": "kasuga_lib:wheel",
                  "duration_seconds": 2.0,
                  "bones": [
                    { "bone": "root", "keyframes": [
                      { "time": 0.0, "transform": { "rotate": [0, 0, 0] }, "easing": "linear" },
                      { "time": 1.0, "transform": { "rotate": [0, 180, 0] }, "easing": "ease_in_out_cubic" }
                    ]}
                  ]
                }
                """);
        AnimationClip clip = AnimationClip.CODEC.decode(JsonOps.INSTANCE, json).resultOrPartial(error -> {
            throw new AssertionError(error);
        }).orElseThrow().getFirst();
        assertEquals(Id.parse("kasuga_lib:wheel"), clip.id());
        assertEquals(2f, clip.durationSeconds(), EPS);
        assertEquals(1, clip.bones().size());
        assertEquals(2, clip.bones().get(0).keyframes().size());
        assertEquals("ease_in_out_cubic", AnimationClip.EASING_CODEC.encodeStart(JsonOps.INSTANCE,
                clip.bones().get(0).keyframes().get(1).easing()).result().orElseThrow().getAsString());
    }
}