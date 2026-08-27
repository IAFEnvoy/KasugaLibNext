package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MCBackendTest {
    @Test
    void transformsLocalBoundsByTheInstanceRootTransform() {
        AABB transformed = MCBackend.transformBounds(
                new AABB(0.0, 0.0, 0.0, 1.0, 2.0, 3.0),
                new Transform().translate(10.0f, 20.0f, 30.0f).rotate(0.0f, 0.0f, 90.0f, true),
                null
        );

        assertEquals(8.0, transformed.minX, 1.0e-5);
        assertEquals(10.0, transformed.maxX, 1.0e-5);
        assertEquals(20.0, transformed.minY, 1.0e-5);
        assertEquals(21.0, transformed.maxY, 1.0e-5);
        assertEquals(30.0, transformed.minZ, 1.0e-5);
        assertEquals(33.0, transformed.maxZ, 1.0e-5);
    }
}
