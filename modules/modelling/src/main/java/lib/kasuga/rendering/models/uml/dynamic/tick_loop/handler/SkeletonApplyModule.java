package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.TransformLimitation;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;

public class SkeletonApplyModule implements ModelTickLoopModule {

    private final TransformLimitation limitation;

    public SkeletonApplyModule(TransformLimitation limitation) {
        this.limitation = limitation;
    }

    @Override
    public void tick(Model model, PendingTransform transform, ModelTickLoop loop, float deltaTime) {
        Transform t = transform.process(new Transform(), limitation);
        ModelInstance instance = loop.getInstance();
        instance.getSkeletonInstance().mulTransformRoot(t);
        instance.getSkeletonInstance().tick();
    }

    @Override
    public void destroy(Model model) {}
}
