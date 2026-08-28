package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.backend.schedule.RenderScheduleMode;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmAnimatedModel;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollRuntime;
import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachineBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 模型句柄 —— 上层<b>渲染端</b>操作一个已挂载 UML 模型的唯一入口。
 *
 * <p>分工约定：上层<b>逻辑端</b>操作状态机（FSM/PoseDriver，决定模型"做什么动作"），
 * 上层<b>渲染端</b>操作本句柄（决定模型"放在哪、是否可见、走哪条调度路径"）。
 * 两者作用于同一个 {@link ModelInstance} 的不同层面——状态机写骨骼局部变换，
 * 句柄写骨架根变换与渲染调度，互不冲突，共同构成模型的完整行为。</p>
 *
 * <p>生命周期语义与资源管线对齐：模型资源尚未发布时 {@link #mount()} 返回
 * false，宿主每 tick 重试即可；挂载前的姿态修改会暂存并在挂载成功后生效，
 * 因此句柄可以安全地早于资源加载创建。若实例由逻辑端先行创建
 * （如 {@code FsmAnimatedModel} 的自愈绑定），用 {@link #ofExisting()} 收编它。</p>
 */
public final class McModelHandle {

    /** Attempts to create/bind the instance; null while the resource is unpublished. */
    @FunctionalInterface
    interface Binder {
        @Nullable ModelInstance bind(@Nullable Transform rootPose);
    }

    private static final String BACKEND = "mc_backend";

    private final ResourceLocation modelLoc;
    @Nullable private final String modelName;
    private final ResourceLocation instanceLoc;

    private final Binder binder;
    private final Function<ResourceLocation, @Nullable ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> pipelineResolver;

    @Nullable
    private ModelInstance instance;
    @Nullable
    private Transform pendingPose;
    private boolean destroyed;
    @Nullable
    private FsmAnimatedModel syncedFsm;

    private McModelHandle(ResourceLocation modelLoc, @Nullable String modelName,
                          ResourceLocation instanceLoc,
                          Binder binder,
                          Function<ResourceLocation, @Nullable ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> pipelineResolver) {
        this.modelLoc = Objects.requireNonNull(modelLoc, "modelLoc");
        this.modelName = modelName;
        this.instanceLoc = Objects.requireNonNull(instanceLoc, "instanceLoc");
        this.binder = binder;
        this.pipelineResolver = pipelineResolver;
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    /**
     * 创建指向全局内容管线的句柄（.mmd.zip / .glb / .obj / .geo.json / JE json）。
     * 句柄可先于资源发布创建；{@link #mount()} 未成功前其余操作安全空转或暂存。
     */
    public static McModelHandle of(ResourceLocation modelLoc, @Nullable String modelName,
                                   ResourceLocation instanceLoc, @Nullable Vec3 pos) {
        McModelHandle handle = new McModelHandle(modelLoc, modelName, instanceLoc,
                pose -> KasugaModelPipelines.createAndBind(modelLoc, instanceLoc, modelName, pose),
                PipelineRegistry::resolve);
        if (pos != null) handle.setPos(pos);
        return handle;
    }

    /**
     * 收编一个已由其它组件（典型：逻辑端的 {@code FsmAnimatedModel}）创建并绑定
     * 的实例。渲染端不重复建实例，只在其上施加摆放/调度/锚点等渲染上下文。
     */
    public static McModelHandle ofExisting(ResourceLocation modelLoc, @Nullable String modelName,
                                           ResourceLocation instanceLoc) {
        return new McModelHandle(modelLoc, modelName, instanceLoc,
                pose -> {
                    ModelInstance adopted = KasugaModelPipelines.createAndBind(
                            modelLoc, instanceLoc, modelName, pose);
                    if (adopted != null && pose != null) {
                        adopted.getSkeletonInstance().transformRoot(pose.copy());
                    }
                    return adopted;
                },
                PipelineRegistry::resolve);
    }

    /** 测试与自定义管线用的底层工厂：显式提供绑定策略。 */
    public static McModelHandle custom(ResourceLocation modelLoc, @Nullable String modelName,
                                       ResourceLocation instanceLoc,
                                       Binder binder,
                                       Function<ResourceLocation, @Nullable ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> pipelineResolver) {
        return new McModelHandle(modelLoc, modelName, instanceLoc, binder, pipelineResolver);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** Attempts binding; idempotent. Re-applies the latest buffered pose on success. */
    public boolean mount() {
        if (destroyed || isMounted()) return isMounted();
        ModelInstance bound = binder.bind(pendingPose);
        if (bound == null) return false;
        instance = bound;
        if (pendingPose != null) {
            bound.getSkeletonInstance().transformRoot(pendingPose.copy());
            pendingPose = null;
        }
        return true;
    }

    public boolean isMounted() {
        return instance != null && !destroyed;
    }

    /** Detaches from the render backend; scheduling state is dropped. Returns whether it was mounted. */
    public boolean unmount() {
        if (destroyed || instance == null) return false;
        ModelRenderScheduler.detach(instance);
        var pipeline = pipelineResolver.apply(modelLoc);
        if (pipeline != null) {
            pipeline.stopRendering(modelLoc, instanceLoc, BACKEND);
        }
        instance = null;
        return true;
    }

    /**
     * Unmounts and removes the instance entirely; the handle must be discarded afterwards.
     * A synced state machine hosted here is detached too — call
     * {@code fsm.onRemoved(level)} yourself first when sync keys need cleanup.
     */
    public void destroy() {
        if (destroyed) return;
        unmount();
        destroyed = true;
        if (syncedFsm != null) {
            syncedFsm.model(null, null, null);
            syncedFsm = null;
        }
        var pipeline = pipelineResolver.apply(modelLoc);
        if (pipeline != null) {
            pipeline.removeInstance(modelLoc, instanceLoc);
        }
        instance = null;
        pendingPose = null;
    }

    // ------------------------------------------------------------------
    // 姿态（vanilla 风格动词）
    // ------------------------------------------------------------------

    /** World-space root position; buffered while unmounted and applied on mount. */
    public McModelHandle setPos(Vec3 pos) {
        Objects.requireNonNull(pos, "pos");
        ensurePose().setPosition(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
        flushPose();
        return this;
    }

    public McModelHandle setPos(double x, double y, double z) {
        return setPos(new Vec3(x, y, z));
    }

    @Nullable
    public Vec3 getPos() {
        Transform pose = currentPose();
        if (pose == null) return null;
        Vector3f p = pose.getPosition();
        return new Vec3(p.x, p.y, p.z);
    }

    /** Moves relative to the current position (world-space translation). */
    public McModelHandle move(Vec3 delta) {
        Objects.requireNonNull(delta, "delta");
        ensurePose().translateWorld(new Vector3f((float) delta.x, (float) delta.y, (float) delta.z));
        flushPose();
        return this;
    }

    /** Sets the root rotation as XYZ Euler degrees (vanilla convention). */
    public McModelHandle setRotationDegrees(float x, float y, float z) {
        Transform pose = ensurePose();
        Vector3f position = pose.getPosition();
        Vector3f scale = pose.transform().getScale(new Vector3f());
        pose.set(new org.joml.Matrix4f().translationRotateScale(position,
                new Quaternionf().rotationXYZ(
                        (float) Math.toRadians(x), (float) Math.toRadians(y), (float) Math.toRadians(z)),
                scale));
        flushPose();
        return this;
    }

    /** Rotates by additional XYZ Euler degrees. */
    public McModelHandle rotateByDegrees(float x, float y, float z) {
        ensurePose().rotate(x, y, z, true);
        flushPose();
        return this;
    }

    /** Resets root to identity. */
    public McModelHandle resetPose() {
        ensurePose().setIdentity();
        flushPose();
        return this;
    }

    /** Advanced: direct mutable access to the root transform (flushed on every prior call). */
    public Transform pose() {
        return ensurePose();
    }

    private Transform ensurePose() {
        if (pendingPose == null) {
            pendingPose = new Transform();
            if (instance != null) {
                pendingPose.set(instance.getSkeletonInstance().getTransform());
            }
        }
        return pendingPose;
    }

    private void flushPose() {
        if (instance != null && pendingPose != null) {
            instance.getSkeletonInstance().transformRoot(pendingPose.copy());
        }
    }

    @Nullable
    private Transform currentPose() {
        if (instance != null) return instance.getSkeletonInstance().getTransform();
        return pendingPose;
    }

    // ------------------------------------------------------------------
    // 渲染调度（对接原版渲染器机制）
    // ------------------------------------------------------------------

    /** Vanilla owns visibility: an {@code EntityRenderer}/{@code BER} adapter marks each frame. */
    public McModelHandle scheduleVanillaRenderer() {
        if (instance != null) ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);
        return this;
    }

    /** Legacy global-pipeline behavior: draw every frame (frustum/distance gates still apply). */
    public McModelHandle scheduleAlways() {
        if (instance != null) ModelRenderScheduler.setMode(instance, RenderScheduleMode.ALWAYS);
        return this;
    }

    public McModelHandle show() {
        if (instance != null) ModelRenderScheduler.setVisible(instance, true);
        return this;
    }

    public McModelHandle hide() {
        if (instance != null) ModelRenderScheduler.setVisible(instance, false);
        return this;
    }

    /** Whether the current schedule allows rendering this frame (mounted instances only). */
    public boolean shown() {
        return instance != null && ModelRenderScheduler.shouldRender(instance);
    }

    /** Per-instance view-distance cap in blocks; 0 disables distance culling. */
    public McModelHandle maxRenderDistance(float blocks) {
        if (instance != null) {
            ModelRenderScheduler.setMaxRenderDistance(instance, blocks);
        }
        return this;
    }

    /**
     * Called from inside a vanilla renderer's {@code render()} — proof that
     * vanilla passed its own culling this frame.
     */
    public void markRenderedThisFrame() {
        if (instance != null) {
            ModelRenderScheduler.markRenderedThisFrame(instance);
        }
    }

    // ------------------------------------------------------------------
    // 状态机接入（逻辑端）
    // ------------------------------------------------------------------

    /**
     * 挂载一个<b>本地</b>数据驱动状态机：从共享定义桶按 id 构建，装配
     * {@link FsmPoseDriver} 到本实例。无服务器同步——纯客户端表现或纯逻辑机用。
     * 定义未就绪时返回 null（下 tick 重试）。
     *
     * <p>逻辑端每 game tick 调用返回绑定的 {@link LocalFsmBinding#tick()}。</p>
     */
    @Nullable
    public LocalFsmBinding attachLocalStateMachine(Id definitionId) {
        requireMounted();
        var definition = FsmMachineBuilder.findDefinition(definitionId);
        if (definition == null) return null;
        var machine = FsmMachineBuilder
                .build(this, definition, null);
        if (machine == null) return null;
        return attachLocalStateMachine(machine);
    }

    /** 程序化状态机版本：直接接管一个已构建的机器。 */
    public LocalFsmBinding attachLocalStateMachine(StateMachine<?> machine) {
        requireMounted();
        Objects.requireNonNull(machine, "machine");
        FsmPoseDriver driver = new FsmPoseDriver(machine, instance);
        setAnimationDriver(driver);
        return new LocalFsmBinding(this, machine, driver);
    }

    /**
     * 带服务器权威同步的完整宿主：内部托管一个 {@link FsmAnimatedModel}
     * （服务端逻辑机 + 推送；客户端傀儡机 + 驱动），其实例自动收编进本句柄。
     * 句柄必须以 {@link #ofFsm} 创建（instanceLoc 需与 FSM 的派生规则一致）。
     *
     * <p>宿主在 BE/Entity 的 tick 与生命周期里转发：
     * {@code fsm.tick(level, pos)} / {@code fsm.onRemoved(level)} 等。</p>
     */
    public synchronized FsmAnimatedModel attachSyncedStateMachine(
            Object logicOwner, long ownerDiscriminator,
            @Nullable Id stateMachineId) {
        ResourceLocation expected = fsmInstanceLoc(modelLoc, ownerDiscriminator);
        if (!expected.equals(instanceLoc)) {
            throw new IllegalStateException(
                    "attachSyncedStateMachine requires the handle to be created with ofFsm(...): instanceLoc '"
                            + instanceLoc + "' != '" + expected + "'");
        }
        if (syncedFsm != null) {
            throw new IllegalStateException("a synced state machine is already attached to this handle");
        }
        FsmAnimatedModel fsm = new FsmAnimatedModel(logicOwner, ownerDiscriminator,
                this::poseCopyOrNull,
                stateMachineId, modelLoc, modelName,
                null,
                this::adoptFromFsm);
        this.syncedFsm = fsm;
        // 已挂载时无需动作——FSM 的 ensureClientModel 会通过 getInstance 找到同一实例。
        // 未挂载时由 FSM 自行创建并在绑定回调里收编进句柄。
        return fsm;
    }

    /** The internally-hosted synced state machine, if any. */
    @Nullable
    public FsmAnimatedModel syncedStateMachine() {
        return syncedFsm;
    }

    /** Instance identifier used by the FSM integration: {@code <namespace>:fsm_<discriminator>}. */
    public static ResourceLocation fsmInstanceLoc(ResourceLocation modelLoc, long ownerDiscriminator) {
        return ResourceLocation.fromNamespaceAndPath(modelLoc.getNamespace(), "fsm_" + ownerDiscriminator);
    }

    /** Factory aligned with {@link #attachSyncedStateMachine}'s identity derivation. */
    public static McModelHandle ofFsm(ResourceLocation modelLoc, @Nullable String modelName,
                                      long ownerDiscriminator, @Nullable Vec3 pos) {
        return of(modelLoc, modelName, fsmInstanceLoc(modelLoc, ownerDiscriminator), pos);
    }

    /** Test seam: {@link #ofFsm} with a controllable binder (same identity derivation). */
    static McModelHandle ofFsmWithBinder(ResourceLocation modelLoc, @Nullable String modelName,
                                         long ownerDiscriminator, Binder binder) {
        return new McModelHandle(modelLoc, modelName,
                fsmInstanceLoc(modelLoc, ownerDiscriminator), binder, PipelineRegistry::resolve);
    }

    private synchronized void adoptFromFsm(ModelInstance bound) {
        if (destroyed || instance == bound) return;
        instance = bound;
        pendingPose = null; // FSM 在创建实例时已应用 rootTransform supplier 的值
    }

    /** Test seam: simulates the wrapper's client-bind callback firing. */
    synchronized void markExternalBind(ModelInstance bound) {
        adoptFromFsm(bound);
    }

    @Nullable
    private Transform poseCopyOrNull() {
        Transform current = currentPose();
        return current == null ? null : current.copy();
    }

    // ------------------------------------------------------------------
    // 能力面
    // ------------------------------------------------------------------

    /**
     * 逻辑端接入口：为该模型装配动画驱动（典型是状态机派生的
     * {@code FsmPoseDriver}）。逻辑端由此只面向状态机编程。
     */
    public McModelHandle setAnimationDriver(@Nullable PoseDriver driver) {
        requireMounted();
        instance.setPoseDriver(driver);
        return this;
    }

    /** Attaches a display sub-object to a skeleton anchor (e.g. a held item). */
    public boolean attachToAnchor(String anchorName, BiConsumer<String, Transform> receiver) {
        if (instance == null) return false;
        return instance.attachToAnchor(anchorName,
                transform -> receiver.accept(anchorName, transform));
    }

    public boolean detachFromAnchor(String anchorName, BiConsumer<String, Transform> receiver) {
        if (instance == null) return false;
        return instance.detachFromAnchor(anchorName,
                transform -> receiver.accept(anchorName, transform));
    }

    /**
     * Enables PMX/glTF ragdoll physics with automatic render-frame stepping,
     * or returns {@code null} when Box3D is unavailable.
     */
    @Nullable
    public MmdRagdoll enablePhysics(MinecraftRagdollConfig.UpdateMode updateMode) {
        requireMounted();
        MmdRagdoll ragdoll = instance.enablePhysics();
        if (ragdoll == null) return null;
        MinecraftRagdollRuntime.register(instance, updateMode);
        return ragdoll;
    }

    public void disablePhysics() {
        if (instance == null) return;
        MinecraftRagdollRuntime.unregister(instance);
        instance.disablePhysics();
    }

    /** Escape hatch: full tick-loop module access (pre-IK / post-IK / post-physics mounting). */
    public lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop tickLoop() {
        requireMounted();
        return instance.getTickLoop();
    }

    /** Escape hatch: the underlying instance for APIs not covered by the handle. */
    @Nullable
    public ModelInstance instance() {
        return instance;
    }

    public ResourceLocation modelLoc() { return modelLoc; }
    @Nullable public String modelName() { return modelName; }
    public ResourceLocation instanceLoc() { return instanceLoc; }

    private void requireMounted() {
        if (!isMounted()) {
            throw new IllegalStateException("model '" + modelLoc + "' (instance '" + instanceLoc + "') is not mounted yet");
        }
    }

    /**
     * 逻辑端持有的本地状态机绑定：机器 + 已装配的驱动。逻辑端只面向
     * {@link #machine()} 编程（触发变量、查询状态）；每 game tick 调
     * {@link #tick()} 推进并发布姿态目标，渲染线程的插值采样由管线完成。
     */
    public static final class LocalFsmBinding {
        private final McModelHandle handle;
        private final StateMachine<?> machine;
        private final FsmPoseDriver driver;

        private LocalFsmBinding(McModelHandle handle,
                                StateMachine<?> machine,
                                FsmPoseDriver driver) {
            this.handle = handle;
            this.machine = machine;
            this.driver = driver;
        }

        public StateMachine<?> machine() {
            return machine;
        }

        public FsmPoseDriver driver() {
            return driver;
        }

        /** 逻辑端 game-tick 入口：推进机器 + 发布姿态目标。 */
        public void tick() {
            tick(1f / 20f);
        }

        public void tick(float dtSeconds) {
            driver.tick(dtSeconds);
        }

        /** 解除绑定：卸下驱动，机器状态保留。 */
        public void dispose() {
            handle.setAnimationDriver(null);
        }
    }
}
