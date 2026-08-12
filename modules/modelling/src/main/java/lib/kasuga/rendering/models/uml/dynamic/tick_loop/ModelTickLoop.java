package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.structure.Model;
import lombok.Getter;

public class ModelTickLoop {
    @Getter
    private final TickLoopPipeline<ModelTickLoopModule> pipeline = new TickLoopPipeline<>();
    @Getter
    private final ModelInstance instance;
    private final PendingTransform transform;

    public ModelTickLoop(ModelInstance instance, PendingTransform transform) {
        this.instance = instance;
        this.transform = transform;
    }

    public Model getModel() {
        return instance.getModel();
    }

    public void tick(float deltaTime) {
        this.tickWithTransform(this.transform, deltaTime);
    }

    public void tickWithTransform(PendingTransform transform, float deltaTime) {
        Model model = instance.getModel();
        for (var h : this.pipeline.list()) {
            h.tick(model, transform, this, deltaTime);
        }
    }

    public void destroy() {
        Model model = instance.getModel();
        for (var h : this.pipeline.list()) {
            h.destroy(model);
        }
    }
}
