package lib.kasuga.rendering.models.uml.backend;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lombok.Getter;
import lombok.Setter;

public abstract class BackendContext<
        BridgeType extends Bridge,
        BackendRenderableType,
        BackendContextType,
        BackendTransformType> implements AutoCloseable {

    @Getter
    private final BridgeType bridge;

    private BackendRenderableType cache;
    private long skeletonVersion;

    @Getter
    private final ModelInstance modelInstance;

    @Setter
    @Getter
    private boolean render;

    public BackendContext(BridgeType bridge, ModelInstance modelInstance) {
        this.bridge = bridge;
        this.modelInstance = modelInstance;
        this.cache = null;
        this.skeletonVersion = Long.MIN_VALUE;
        this.render = true;
    }

    @SuppressWarnings("unchecked")
    public BackendRenderableType apply() {
        SkeletonInstance skeleton = modelInstance.getSkeletonInstance();
        long tickStart = ModelProfiler.start();
        skeleton.tick();
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("skeleton.tick", tickStart,
                    "version=" + skeleton.getVersion() +
                            ", full=" + skeleton.isLastFullUpdate() +
                            ", dirtyBones=" + skeleton.getLastDirtyBones().size());
        }
        long currentVersion = skeleton.getVersion();
        if (cache != null && skeletonVersion == currentVersion) return cache;
        if (cache instanceof VersionedBackendRenderable versioned) {
            long updateStart = ModelProfiler.start();
            versioned.updateForVersion(modelInstance, bridge);
            if (ModelProfiler.enabled()) {
                ModelProfiler.record("backend.updateForVersion", updateStart,
                        "version=" + currentVersion);
            }
            skeletonVersion = currentVersion;
            return cache;
        }
        closeCache();
        long buildStart = ModelProfiler.start();
        cache = (BackendRenderableType) bridge.apply(modelInstance);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("backend.buildRenderable", buildStart,
                    "version=" + currentVersion);
        }
        skeletonVersion = currentVersion;
        return cache;
    }

    private void closeCache() {
        if (cache instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }
        cache = null;
    }

    public abstract BackendTransformType beforeRender(BackendContextType context);

    @Override
    public void close() throws Exception {
        closeCache();
    }
}
