package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.TransformLimitation;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;

public class SkeletonApplyModule implements ModelTickLoopModule {

    private final TransformLimitation limitation;

    public SkeletonApplyModule(TransformLimitation limitation) {
        this.limitation = limitation;
    }

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        ModelInstance instance = loop.getInstance();
        instance.getSkeletonInstance().transformRoot(
                transforms[0].process(new Transform(), limitation));

        Bone[] bones = model.getSkeleton().getBones();
        for (int i = 0; i < bones.length; i++) {
            Transform transform = transforms[i + 1].process(new Transform(), limitation);
            instance.getSkeletonInstance().transform(bones[i], transform);
        }
        instance.getSkeletonInstance().tick();
    }

    @Override
    public void destroy(Model model) {}
}
