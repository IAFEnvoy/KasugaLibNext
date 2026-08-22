package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.BodyShape;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.BodyContact;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.GenericRigidBody;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftBlockRigidBodyTest {
    @Test
    void voxelBoxesBecomeBlockCenteredCompoundShapes() {
        List<BodyShape> shapes = MinecraftBlockRigidBody.collisionShapes(Shapes.or(
                Shapes.box(0d, 0d, 0d, 1d, 0.5d, 1d),
                Shapes.box(0.25d, 0.5d, 0.25d, 0.75d, 1d, 0.75d)));

        assertEquals(2, shapes.size());
        BodyShape.Box slab = (BodyShape.Box) shapes.getFirst();
        assertEquals(new Vector3f(0f, -0.25f, 0f), slab.center());
        assertEquals(new Vector3f(0.5f, 0.25f, 0.5f), slab.halfExtents());
        BodyShape.Box post = (BodyShape.Box) shapes.get(1);
        assertEquals(new Vector3f(0f, 0.25f, 0f), post.center());
        assertEquals(new Vector3f(0.25f), post.halfExtents());
    }

    @Test
    void playerContactClipsInwardVelocityWithoutReapplyingBox3dImpulse() {
        GenericRigidBody player = GenericRigidBody.kinematic(
                lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody.SHAPE_BOX,
                new Vector3f(0.3f, 0.9f, 0.3f));
        GenericRigidBody block = GenericRigidBody.box(new Vector3f(0.5f), 1f);
        BodyContact floor = new BodyContact(player, java.util.Optional.of(block),
                new Vector3f(), new Vector3f(0f, 1f, 0f),
                -0.04f, 80f, 160f, -20f);

        Vec3 clipped = MinecraftBlockPhysics.clipVelocityAgainstContacts(
                new Vec3(0.2d, -1.5d, -0.3d), List.of(floor));
        assertEquals(0.2d, clipped.x, 1e-6d);
        assertEquals(0d, clipped.y, 1e-6d,
                "contact response removes falling speed instead of adding totalNormalImpulse");
        assertEquals(-0.3d, clipped.z, 1e-6d);
        Vector3f correction = MinecraftBlockPhysics.penetrationCorrection(List.of(floor));
        assertEquals(0f, correction.x, 1e-6f);
        assertEquals(0.041f, correction.y, 1e-6f);
        assertEquals(0f, correction.z, 1e-6f);
    }

    @Test
    void playerPenetrationUsesOneDeepestNormalAndIsCapped() {
        GenericRigidBody player = GenericRigidBody.kinematic(
                lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody.SHAPE_BOX,
                new Vector3f(0.3f, 0.9f, 0.3f));
        GenericRigidBody block = GenericRigidBody.box(new Vector3f(0.5f), 1f);
        BodyContact shallowWall = new BodyContact(player, java.util.Optional.of(block),
                new Vector3f(), new Vector3f(1f, 0f, 0f), -0.03f, 0f, 0f, 0f);
        BodyContact deepFloor = new BodyContact(player, java.util.Optional.of(block),
                new Vector3f(), new Vector3f(0f, 1f, 0f), -0.4f, 0f, 0f, 0f);

        assertEquals(new Vector3f(0f, 0.1f, 0f),
                MinecraftBlockPhysics.penetrationCorrection(List.of(shallowWall, deepFloor)));
    }
}
