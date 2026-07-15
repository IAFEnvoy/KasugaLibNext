package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.structure.Model;

public interface ModelTickLoopModule {

    void tick(Model model, PendingTransform transform, ModelTickLoop loop, float deltaTime);

    void destroy(Model model);
}
