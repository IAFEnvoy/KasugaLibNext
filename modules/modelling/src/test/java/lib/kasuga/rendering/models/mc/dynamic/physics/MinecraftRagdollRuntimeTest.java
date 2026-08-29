package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.physics.MmdPhysicsScene;
import org.joml.Vector3d;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("box3d")
class MinecraftRagdollRuntimeTest {
    @Test
    void closedSharedScenesAreRemovedBeforeTheNextRuntimeSnapshot() {
        int baseline = MinecraftRagdollRuntime.registeredSceneCount();
        MmdPhysicsScene scene = new MmdPhysicsScene(new Vector3d(), 1);
        MinecraftRagdollRuntime.register(scene, MinecraftRagdollConfig.UpdateMode.RENDER_FRAME);
        assertEquals(baseline + 1, MinecraftRagdollRuntime.registeredSceneCount());

        scene.close();

        assertEquals(baseline, MinecraftRagdollRuntime.registeredSceneCount());
    }
}
