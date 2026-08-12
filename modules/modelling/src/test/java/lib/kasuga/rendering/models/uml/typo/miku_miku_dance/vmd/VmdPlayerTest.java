package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VmdPlayerTest {
    @Test
    void samplesBoneMorphAndStepTracksAtFractionalFrames() {
        VmdMotion motion = motion();
        VmdPlayer player = new VmdPlayer(motion, new Vector3f(0.5f));

        VmdPose pose = player.sampleFrame(5.0);

        assertEquals(new Vector3f(5, 10, 15), pose.bones().get("root").getPosition());
        assertEquals(0.5f, pose.morphs().get("smile"), 1e-5f);
        assertTrue(pose.properties().visible());
        assertEquals(10, player.maxFrame());
    }

    @Test
    void appliesSampledBoneTransformToModelInstance() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], new Bone[]{root}, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);

        new VmdPlayer(motion(), new Vector3f(0.5f)).apply(instance, 5);

        assertEquals(new Vector3f(5, 10, 15),
                instance.getSkeletonInstance().getTransforms().get(root).getPosition());
    }

    private static VmdMotion motion() {
        BoneInterpolation interpolation = BoneInterpolation.from(linearBoneInterpolation());
        List<BoneKeyframe> bones = List.of(
                new BoneKeyframe(0, new Vector3f(), new Quaternionf(), interpolation),
                new BoneKeyframe(10, new Vector3f(20, 40, 60), new Quaternionf(), interpolation));
        List<MorphKeyframe> morphs = List.of(new MorphKeyframe(0, 0), new MorphKeyframe(10, 1));
        List<PropertyKeyframe> properties = List.of(
                new PropertyKeyframe(0, true, List.of()),
                new PropertyKeyframe(10, false, List.of()));
        return new VmdMotion("Vocaloid Motion Data 0002", "model", Map.of("root", bones),
                Map.of("smile", morphs), List.of(), List.of(), List.of(), properties, new byte[0]);
    }

    private static byte[] linearBoneInterpolation() {
        byte[] raw = new byte[64];
        for (int channel = 0; channel < 4; channel++) {
            raw[channel] = 20;
            raw[channel + 4] = 20;
            raw[channel + 8] = 107;
            raw[channel + 12] = 107;
        }
        raw[17] = 20;
        raw[18] = 20;
        return raw;
    }
}
