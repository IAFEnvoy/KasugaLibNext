package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link ClipSampler} formula branch end-to-end at the JVM level for the v2.0 fan clip
 * ({@code kasuga_lib:fan_fsm}): the fan/cover/group rotations are driven purely by {@code query.angle}
 * (the decorative speed integral) — fan rotor local = 11·angle (absolute 12× group), cover sway =
 * {@code sin(rad(2·angle))·30} (12s cycle at gear 1), group =
 * angle. No ramp parameters, no gating variables.
 *
 * <p>This is the JVM-side stand-in for the "bbmodel loader bone exposure" gating item
 * : it validates the produced {@link Pose} contents,
 * which is what the in-game renderer would apply to the named bones.
 */
class ClipSamplerFormulaTest {

    private static final float DELTA = 1e-2f;

    private static AnimationClip fanClip() {
        return new AnimationClip(
                Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm"),
                12f,
                List.of(), List.of(), List.of(),
                List.of(
                        new AnimationClip.FunctionTrack("group", AnimationClip.FunctionChannel.ROTATE,
                                "", "query.angle", ""),
                        new AnimationClip.FunctionTrack("fan", AnimationClip.FunctionChannel.ROTATE,
                                "", "11 * query.angle", ""),
                        new AnimationClip.FunctionTrack("cover", AnimationClip.FunctionChannel.ROTATE,
                                "", "", "sin(rad(2 * query.angle)) * 30")
                )
        );
    }

    /**
     * Warm-up: decoding the clip's formula strings registers the query.* variables in the namespace.
     * {@code Namespace.assign} is a silent no-op for unregistered codecs, so this must run before any assign.
     */
    private static Namespace warmNamespace() {
        Namespace ns = new Namespace(Code.ROOT_NAMESPACE);
        ClipSampler.INSTANCE.sample(fanClip(), 0f, ns);
        return ns;
    }

    private static Pose sample(Namespace ns, float elapsed, float angle) {
        ns.assign("query.angle", angle);
        return ClipSampler.INSTANCE.sample(fanClip(), elapsed, ns);
    }

    private static Quaternionf rotation(Pose pose, String bone) {
        Pose.Bone entry = pose.bones().get(bone);
        assertNotNull(entry, "pose should carry bone '" + bone + "'");
        return entry.transform().getRotation();
    }

    private static void assertRotationEquals(Quaternionf expected, Quaternionf actual, String label) {
        float dot = expected.normalize().dot(actual.normalize());
        // q and -q represent the same rotation; use |dot| to ignore sign flips from matrix extraction.
        assertTrue(Math.abs(dot) > 1f - DELTA,
                "rotation mismatch for " + label + ": expected " + expected + ", actual " + actual + " (dot " + dot + ")");
    }

    /** U8/U7 anchor: group y == θ (absolute), fan local y == 11θ (absolute 12θ = 12× group), cover at the +30° endpoint (2θ=90). */
    @Test
    void angleDrivesRotorCoverAndGroup() {
        Namespace ns = warmNamespace();
        float angle = 45f; // cover endpoint: 2θ=90 → sin(rad(90))·30 = +30 (phase-based, speed-independent)
        Pose pose = sample(ns, angle / 15f, angle);

        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 11f * angle, 0f), rotation(pose, "fan"), "fan rotor (local 11θ)");
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 0f, 30f), rotation(pose, "cover"), "cover +30 endpoint");
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, angle, 0f), rotation(pose, "group"), "group (absolute θ)");
    }

    /** U7 opposite endpoint: angle=3240 → sin(rad(270))·30 = −30. */
    @Test
    void coverReachesNegativeEndpoint() {
        Namespace ns = warmNamespace();
        float angle = 135f; // cover −30° endpoint (2θ=270)
        Pose pose = sample(ns, angle / 15f, angle);

        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 0f, -30f), rotation(pose, "cover"), "cover −30 endpoint");
    }

    /** U7 phase: the cover formula evaluates exactly sin(rad(angle/12))·30 (assert the resulting rotation). */
    @Test
    void coverFormulaMatchesLockedTable() {
        Namespace ns = warmNamespace();
        float angle = 22.5f; // 2θ=45 → sin(rad(45))·30 ≈ 21.213
        Pose pose = sample(ns, angle / 15f, angle);
        float expected = (float) Math.sin(Math.toRadians(2f * angle)) * 30f;
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 0f, expected), rotation(pose, "cover"),
                "cover phase");
    }

    /** Constant angle → the pose is identical at any sample time: OFF freezes the fan (no gating needed). */
    @Test
    void constantAngleFreezesFan() {
        Namespace ns = warmNamespace();
        float angle = 123f;
        Pose atT0 = sample(ns, 0f, angle);
        Pose atT3 = sample(ns, 3f, angle);

        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 11f * angle, 0f), rotation(atT3, "fan"), "fan frozen at angle (local 11θ)");
        assertRotationEquals(rotation(atT0, "fan"), rotation(atT3, "fan"), "fan identical across time");
        assertRotationEquals(rotation(atT0, "cover"), rotation(atT3, "cover"), "cover identical across time");
        assertRotationEquals(rotation(atT0, "group"), rotation(atT3, "group"), "group identical across time");
    }
}