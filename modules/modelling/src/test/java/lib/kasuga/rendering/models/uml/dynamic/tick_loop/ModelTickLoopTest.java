package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.SkeletonApplyModule;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelTickLoopTest {

    @Test
    void allocatesRootAndBoneTransformsInSkeletonOrder() {
        Fixture fixture = fixture();
        ModelTickLoop loop = new ModelTickLoop(fixture.instance());

        assertEquals(3, loop.getTransforms().length);
        assertSame(loop.getTransforms()[0], loop.rootTransform());
        assertSame(loop.getTransforms()[1], loop.boneTransform(0));
        assertSame(loop.getTransforms()[1], loop.boneTransform(fixture.root()));
        assertSame(loop.getTransforms()[2], loop.boneTransform(1));
        assertSame(loop.getTransforms()[2], loop.boneTransform(fixture.child()));
    }

    @Test
    void modulesComposeThenApplyOneTransformPerRootAndBoneWithoutAccumulating() {
        Fixture fixture = fixture();
        ModelTickLoop loop = new ModelTickLoop(fixture.instance());
        loop.getPipeline().addLast("pose", new ModelTickLoopModule() {
            @Override
            public void tick(Model model, PendingTransform[] transforms,
                             ModelTickLoop tickLoop, float deltaTime) {
                transforms[0].setOffsetZ(4f);
                transforms[fixture.root().getIndex() + 1].setOffsetX(2f);
                transforms[fixture.child().getIndex() + 1].setOffsetY(3f);
            }

            @Override
            public void destroy(Model model) {
            }
        });
        loop.getPipeline().addLast("apply", new SkeletonApplyModule(new TransformLimitation()));

        loop.tick(1f / 20f);
        loop.tick(1f / 20f);

        assertEquals(new Vector3f(0f, 0f, 4f),
                fixture.instance().getSkeletonInstance().getTransform().getPosition());
        assertEquals(new Vector3f(2f, 0f, 0f),
                fixture.instance().getSkeletonInstance().getTransforms().get(fixture.root()).getPosition());
        assertEquals(new Vector3f(0f, 3f, 0f),
                fixture.instance().getSkeletonInstance().getTransforms().get(fixture.child()).getPosition());
        assertEquals(new Vector3f(2f, 3f, 4f),
                fixture.instance().getSkeletonInstance().getAbsoluteTransforms().get(fixture.child()).getPosition());
    }

    @Test
    void rejectsArraysThatDoNotMatchRootPlusBoneLayout() {
        Fixture fixture = fixture();

        assertThrows(IllegalArgumentException.class,
                () -> new ModelTickLoop(fixture.instance(), new PendingTransform[2]));
        PendingTransform[] withNullSlot = {
                new PendingTransform(), new PendingTransform(), null
        };
        assertThrows(IllegalArgumentException.class,
                () -> new ModelTickLoop(fixture.instance(), withNullSlot));
    }

    private static Fixture fixture() {
        Bone root = new Bone("root", new Transform(), null);
        Bone child = new Bone("child", new Transform(), null);
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        child.setChildren(new Bone[0]);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new Fixture(new ModelInstance(model, null, null, null, null, null), root, child);
    }

    private record Fixture(ModelInstance instance, Bone root, Bone child) {
    }
}
