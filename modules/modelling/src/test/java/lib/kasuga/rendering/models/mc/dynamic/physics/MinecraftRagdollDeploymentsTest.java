package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRagdollDeploymentsTest {
    @Test
    void requestOwnsADefensiveRootTransform() {
        Transform source = new Transform().translate(1f, 2f, 3f);
        MinecraftRagdollDeployments.Request request = new MinecraftRagdollDeployments.Request(
                id("models/pmx/model.mmd.zip"), "Model.pmx", id("instances/one"),
                id("ragdolls/model.json"), source, true);

        source.translate(10f, 0f, 0f);
        assertEquals(new Vector3f(1f, 2f, 3f), request.rootTransform().getPosition());

        Transform returned = request.rootTransform();
        returned.translate(0f, 10f, 0f);
        assertEquals(new Vector3f(1f, 2f, 3f), request.rootTransform().getPosition());
        assertTrue(request.applyInitialState());
    }

    @Test
    void manifestRequestDoesNotRequireAHardCodedRagdollResource() {
        MinecraftRagdollDeployments.Request request = new MinecraftRagdollDeployments.Request(
                id("models/character.glb"), id("instances/gltf"), new Transform(), true);

        assertEquals(id("models/character.glb"), request.modelResource());
        assertEquals("", request.modelName());
        assertEquals(null, request.configResource());
    }

    @Test
    void removingAnUnknownDeploymentIsIdempotent() {
        ResourceLocation missing = id("instances/missing");
        assertTrue(MinecraftRagdollDeployments.get(missing).isEmpty());
        assertFalse(MinecraftRagdollDeployments.remove(missing));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("ragdoll_deployment_test", path);
    }
}
