package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Client runtime API for deploying and removing configured PMX or glTF ragdolls. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class MinecraftRagdollDeployments {
    private static final String BRIDGE = "mc_bridge";
    private static final String BACKEND = "mc_backend";
    private static final Map<ResourceLocation, DeploymentHandle> DEPLOYMENTS =
            new HashMap<>();
    /** Guarded by the class monitor; lets the frame loop exit early. */
    private static int ANCHOR_COUNT;

    private MinecraftRagdollDeployments() {}

    /** Deploys using the active client resource manager and level. */
    public static Optional<RagdollDeployment> deploy(Request request) throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        return deploy(request, minecraft.getResourceManager(), () -> minecraft.level);
    }

    /**
     * Deploys one configured ragdoll. An empty result means the routed model
     * has not been published yet; callers may retry after a
     * resource reload. Duplicate live instance ids are rejected.
     */
    public static synchronized Optional<RagdollDeployment> deploy(
            Request request, ResourceManager resourceManager,
            Supplier<? extends Level> levelSupplier) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resourceManager, "resourceManager");
        Objects.requireNonNull(levelSupplier, "levelSupplier");

        DeploymentHandle previous = DEPLOYMENTS.get(request.instanceId);
        if (previous != null) {
            if (previous.active()) {
                throw new IllegalStateException("ragdoll instance is already deployed: " + request.instanceId);
            }
            previous.remove();
        }

        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline =
                PipelineRegistry.resolve(request.modelResource);
        if (pipeline == null) return Optional.empty();
        ResourceLocation resolvedModel = request.modelResource;
        if (pipeline == PipelineRegistry.pmx()) {
            var loader = PipelineRegistry.pmxLoader();
            if (loader == null) return Optional.empty();
            resolvedModel = loader.getLocByFileAndName(request.modelResource, request.modelName);
        }
        if (resolvedModel == null || !pipeline.hasModel(resolvedModel)) return Optional.empty();

        ResourceLocation configResource = request.configResource;
        if (configResource == null && pipeline == PipelineRegistry.gltf()) {
            var loader = PipelineRegistry.gltfLoader();
            configResource = loader == null ? null : loader.manifest(resolvedModel)
                    .map(manifest -> manifest.ragdollConfig()).orElse(null);
        }
        if (configResource == null) {
            throw new IOException("Model manifest does not declare a ragdoll config: " + resolvedModel);
        }
        MinecraftRagdollConfig config = MinecraftRagdollConfig.load(resourceManager, configResource);
        if (pipeline.hasInstance(resolvedModel, request.instanceId)) {
            throw new IllegalStateException("pipeline instance id is already in use: " + request.instanceId);
        }
        ModelInstance instance = pipeline.createInstance(
                resolvedModel, request.instanceId, null, null, null);
        if (instance == null) return Optional.empty();

        try {
            instance.getSkeletonInstance().transformRoot(request.rootTransform);
            MmdRagdoll ragdoll = config.attach(instance, levelSupplier, request.applyInitialState);
            pipeline.addToRenderer(resolvedModel, request.instanceId, BRIDGE, BACKEND);
            DeploymentHandle handle = new DeploymentHandle(request, resolvedModel, configResource,
                    pipeline, instance, ragdoll);
            DEPLOYMENTS.put(request.instanceId, handle);
            return Optional.of(handle);
        } catch (RuntimeException exception) {
            MinecraftRagdollRuntime.unregister(instance);
            MinecraftRagdollDragger.unregister(instance);
            pipeline.removeInstance(resolvedModel, request.instanceId);
            throw exception;
        }
    }

    public static synchronized Optional<RagdollDeployment> get(ResourceLocation instanceId) {
        DeploymentHandle handle = DEPLOYMENTS.get(Objects.requireNonNull(instanceId, "instanceId"));
        if (handle == null) return Optional.empty();
        if (handle.active()) return Optional.of(handle);
        handle.remove();
        return Optional.empty();
    }

    /** Stable snapshot of every live deployment, sorted by instance id. */
    public static synchronized List<RagdollDeployment> active() {
        ArrayList<RagdollDeployment> result = new ArrayList<>(DEPLOYMENTS.size());
        ArrayList<Map.Entry<ResourceLocation, DeploymentHandle>> entries =
                new ArrayList<>(DEPLOYMENTS.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<ResourceLocation, DeploymentHandle> entry : entries) {
            if (entry.getValue().active()) result.add(entry.getValue());
            else entry.getValue().remove();
        }
        return result;
    }

    /** Instance ids currently deployed, for tab completion and diagnostics. */
    public static synchronized List<ResourceLocation> instanceIds() {
        List<RagdollDeployment> live = active();
        List<ResourceLocation> ids = new ArrayList<>(live.size());
        for (RagdollDeployment deployment : live) ids.add(deployment.instanceId());
        return ids;
    }

    public static synchronized boolean remove(ResourceLocation instanceId) {
        DeploymentHandle handle = DEPLOYMENTS.get(Objects.requireNonNull(instanceId, "instanceId"));
        return handle != null && handle.remove();
    }

    public static synchronized int removeAll() {
        int removed = 0;
        for (DeploymentHandle handle : new ArrayList<>(DEPLOYMENTS.values())) {
            if (handle.remove()) removed++;
        }
        return removed;
    }

    /** Detaches the anchor owned by this instance, if any. Mouse drags call this. */
    static void cancelAnchorFor(ModelInstance instance) {
        ArrayList<DeploymentHandle> handles;
        synchronized (MinecraftRagdollDeployments.class) {
            handles = new ArrayList<>(DEPLOYMENTS.values());
        }
        for (DeploymentHandle handle : handles) {
            if (handle.instance == instance) handle.detachAnchor();
        }
    }

    /**
     * Advances every entity anchor once per render frame so hanging bodies
     * track their entity with a fresh target velocity. Runs on the same main
     * thread as physics; at most one frame of latency relative to the step.
     */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) return;
        ArrayList<DeploymentHandle> handles;
        synchronized (MinecraftRagdollDeployments.class) {
            if (ANCHOR_COUNT == 0) return;
            handles = new ArrayList<>(DEPLOYMENTS.values());
        }
        float deltaSeconds = event.getPartialTick().getRealtimeDeltaTicks() / 20f;
        for (DeploymentHandle handle : handles) {
            handle.updateAnchor(level, deltaSeconds);
        }
    }

    public record Request(ResourceLocation modelResource, String modelName,
                          ResourceLocation instanceId, ResourceLocation configResource,
                          Transform rootTransform, boolean applyInitialState) {
        public Request {
            Objects.requireNonNull(modelResource, "modelResource");
            Objects.requireNonNull(modelName, "modelName");
            Objects.requireNonNull(instanceId, "instanceId");
            rootTransform = rootTransform == null ? new Transform() : rootTransform.copy();
        }

        @Override
        public Transform rootTransform() {
            return rootTransform.copy();
        }

        /** Uses the model manifest to resolve its ragdoll config. */
        public Request(ResourceLocation modelResource, ResourceLocation instanceId,
                       Transform rootTransform, boolean applyInitialState) {
            this(modelResource, "", instanceId, null, rootTransform, applyInitialState);
        }
    }

    private static final class DeploymentHandle implements RagdollDeployment {
        private final Request request;
        private final ResourceLocation resolvedModel;
        private final ResourceLocation resolvedConfig;
        private final ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline;
        private final ModelInstance instance;
        private final MmdRagdoll ragdoll;
        private Entity anchoredEntity;
        private MmdRagdoll.Body anchoredBody;

        private DeploymentHandle(Request request, ResourceLocation resolvedModel,
                                 ResourceLocation resolvedConfig,
                                 ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline,
                                 ModelInstance instance, MmdRagdoll ragdoll) {
            this.request = request;
            this.resolvedModel = resolvedModel;
            this.resolvedConfig = resolvedConfig;
            this.pipeline = pipeline;
            this.instance = instance;
            this.ragdoll = ragdoll;
        }

        @Override public ResourceLocation instanceId() { return request.instanceId; }
        @Override public ResourceLocation modelResource() { return request.modelResource; }
        @Override public String modelName() { return request.modelName; }
        @Override public ResourceLocation configResource() { return resolvedConfig; }
        @Override public ModelInstance instance() { return instance; }
        @Override public MmdRagdoll ragdoll() { return ragdoll; }
        @Override public Entity anchoredEntity() { return anchoredEntity; }

        @Override
        public boolean anchorTo(Entity entity) {
            Objects.requireNonNull(entity, "entity");
            synchronized (MinecraftRagdollDeployments.class) {
                if (!active() || anchoredEntity == entity) return active();
                // A held mouse constraint owns the drag slot; anchoring would
                // silently steal it and the two targets would fight.
                if (anchoredEntity != null || ragdoll.dragging()) return false;
                MmdRagdoll.Body body = nearestDynamicBody(entity);
                if (body == null) return false;
                if (!ragdoll.beginDrag(body, anchorTarget(entity))) return false;
                anchoredEntity = entity;
                anchoredBody = body;
                ANCHOR_COUNT++;
                return true;
            }
        }

        @Override
        public boolean detachAnchor() {
            synchronized (MinecraftRagdollDeployments.class) {
                if (anchoredEntity == null) return false;
                anchoredEntity = null;
                anchoredBody = null;
                ANCHOR_COUNT--;
                // Anchors own the constraint while set: mouse drags detach the
                // anchor first, so ending the drag here can never interrupt one.
                ragdoll.endDrag();
                return true;
            }
        }

        void updateAnchor(ClientLevel level, float deltaSeconds) {
            Entity entity = anchoredEntity;
            if (entity == null) return;
            if (entity.isRemoved() || entity.level() != level) {
                detachAnchor();
                return;
            }
            ragdoll.updateDragTarget(anchorTarget(entity), deltaSeconds);
        }

        private Vector3f anchorTarget(Entity entity) {
            return new Vector3f((float) entity.getX(), (float) entity.getEyeY(), (float) entity.getZ());
        }

        private MmdRagdoll.Body nearestDynamicBody(Entity entity) {
            double x = entity.getX();
            double y = entity.getEyeY();
            double z = entity.getZ();
            MmdRagdoll.Body best = null;
            float bestDistanceSquared = Float.POSITIVE_INFINITY;
            for (MmdRagdoll.Body body : ragdoll.bodies()) {
                if (body.source().mode() == 0 || body.source().mass() <= 0f) continue;
                Vector3f position = body.position();
                float dx = position.x - (float) x;
                float dy = position.y - (float) y;
                float dz = position.z - (float) z;
                float distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    best = body;
                }
            }
            return best;
        }

        @Override
        public boolean active() {
            synchronized (MinecraftRagdollDeployments.class) {
                return DEPLOYMENTS.get(request.instanceId) == this
                        && pipeline.getInstance(resolvedModel, request.instanceId) == instance;
            }
        }

        @Override
        public boolean remove() {
            synchronized (MinecraftRagdollDeployments.class) {
                if (!DEPLOYMENTS.remove(request.instanceId, this)) return false;
                if (anchoredEntity != null) {
                    anchoredEntity = null;
                    anchoredBody = null;
                    ANCHOR_COUNT--;
                }
                MinecraftRagdollRuntime.unregister(instance);
                MinecraftRagdollDragger.unregister(instance);
                if (!pipeline.removeInstance(resolvedModel, request.instanceId)) {
                    instance.close();
                }
                return true;
            }
        }
    }
}
