package lib.kasuga.rendering.models.mc.backend.data_type;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.mc.backend.KsgVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.mc.backend.MCBackendContext;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;

public class MCRenderableContext extends BackendContext<MCBridge, KsgVertexBuffer, MCBackendContext, MCBackend.BackendTransform> {

    private static final MCBackend.BackendTransform DEFAULT_TRANSFORM = new MCBackend.BackendTransform(
            new Vector3f(), null, null,
            false, false, true, true,
            1f, 1f, -1, -1
    );

    public MCRenderableContext(MCBridge bridge, ModelInstance modelInstance) {
        super(bridge, modelInstance);
    }

    @Override
    public MCBackend.BackendTransform beforeRender(MCBackendContext context) {
        applyRootTransform(context.getPoseStack(), getModelInstance().getSkeletonInstance().getTransform());
        return DEFAULT_TRANSFORM;
    }

    public void applyRootTransform(PoseStack pose, Transform transform) {
        PoseStack.Pose p = pose.last();
        p.pose().mul(transform.transform());
        p.normal().mul(transform.normal());
    }
}
