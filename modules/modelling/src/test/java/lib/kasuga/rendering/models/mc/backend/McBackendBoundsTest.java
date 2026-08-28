package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;


import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class McBackendBoundsTest {

    @Test
    void freshlyConstructedInstanceEvaluatesAtTheBindPose() {
        ModelInstance instance = fixture();
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);

        assertTrue(MCBackend.scanEvaluatedBounds(instance, min, max),
                "the constructor performs an initial hierarchy evaluation");
        assertTrue(min.lengthSquared() < 1f && max.lengthSquared() < 1f,
                "an identity-pose two-bone chain stays near the origin");
    }

    @Test
    void boundsFollowPhysicallyDisplacedBonesInsteadOfTheBindPose() {
        ModelInstance instance = fixture();
        Bone root = instance.getModel().getSkeleton().getBones()[0];
        Bone child = instance.getModel().getSkeleton().getBones()[1];

        // Simulate a ragdoll tumble: physics writes LOCAL bone transforms that
        // drag the pose far away from the authored bind-pose volume.
        instance.getSkeletonInstance().transform(child,
                new Transform().translate(40f, -25f, 12f));
        instance.getSkeletonInstance().updateTransform();

        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        assertTrue(MCBackend.scanEvaluatedBounds(instance, min, max));

        assertTrue(max.x >= 40f && min.y <= -25f && max.z >= 12f,
                "the visibility box must cover the displaced bone (" + min + " .. " + max + ")");

        // The bind-pose-only box would have missed it entirely.
        assertFalse(min.x > 0f && max.x < 1f && min.y > -1f,
                "bounds must not stay confined to the bind-pose volume");
    }

    private static ModelInstance fixture() {
        Bone root = new Bone("root", new Transform(), null);
        Bone child = new Bone("child", new Transform(), null);
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        child.setChildren(new Bone[0]);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, null, null);
    }
}
