package lib.kasuga.rendering.models.uml.backend;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lombok.Getter;

import java.util.HashMap;

public abstract class Backend<T extends Bridge, R, Q, E> {

    @Getter
    private final HashMap<Object, BackendContext<T, R, Q, E>> renderingObjects;

    public Backend() {
        this.renderingObjects = new HashMap<>();
    }

    public void add(Object key, Bridge bridge, ModelInstance instance) {
        BackendContext context = bridge.getBackendContext(instance);
        context.apply();
        renderingObjects.put(key, context);
    }

    public boolean contains(Object key) {
        return renderingObjects.containsKey(key);
    }

    public boolean remove(Object key) {
        BackendContext<T, R, Q, E> removed = renderingObjects.remove(key);
        if (removed == null) return false;
        try {
            removed.close();
        } catch (Exception ignored) {}
        return true;
    }

    public void renderAllObjects(Q renderContext) {
        for (BackendContext<T, R, Q, E> renderable : renderingObjects.values()) {
            if (!renderable.isRender()) continue;
            render(renderable, renderContext);
        }
    }

    public abstract void render(BackendContext<T, R, Q, E> renderable, Q renderContext);
}
