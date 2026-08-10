package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncClient;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.FsmSyncKey;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncServer;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Base block entity that hosts a data-driven {@link StateMachine} and drives it every tick on
 * both sides: the server builds a logic-only machine (sink {@code null}), the client additionally
 * binds a {@link ModelInstance} via {@link KasugaModelPipelines} and flushes poses through a
 * {@link ModelInstancePoseSink}.
 *
 * <p>Sync contract: the server pushes authoritative state every tick via {@code FsmSyncServer};
 * the client applies it on the main thread via {@code FsmSyncClient} once the machine is bound to
 * its {@code FsmSyncKey} (packets arriving earlier are dropped, then recovered by the forced
 * heartbeat). Hosts never register into {@link FsmMachines}; definitions are read from the
 * shared definition bucket ({@link FsmRegistries#GLOBAL}).
 */
public class AnimationBlockEntity extends BlockEntity implements AnimationHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Both sides use the same ticker: {@link #tickMachine()} drives everything. */
    public static final BlockEntityTicker<AnimationBlockEntity> TICKER =
            (level, pos, state, blockEntity) -> blockEntity.tickMachine();

    /**
     * Shared server-side FSM sync instance (see {@link FsmSyncServer#GLOBAL} — dedup/heartbeat
     * tables must be global or logout cleanup misses entries).
     */
    public static final FsmSyncServer SYNC_SERVER = FsmSyncServer.GLOBAL;

    @Nullable
    private Id stateMachineId;
    @Nullable
    private ResourceLocation modelLoc;
    @Nullable
    private String modelName;
    private boolean definitionWarned;

    @Nullable
    private volatile StateMachine<?> machine;
    /** Client only: the bound model instance (null on the server / before the model is ready). */
    @Nullable
    private ModelInstance modelInstance;
    private boolean modelBound;
    private int lastLoggedVersion = -1;

    public AnimationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public AnimationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                @Nullable Id stateMachineId,
                                @Nullable ResourceLocation modelLoc, @Nullable String modelName) {
        this(type, pos, state);
        this.stateMachineId = stateMachineId;
        this.modelLoc = modelLoc;
        this.modelName = modelName;
    }

    /** Programmatic machine assignment; takes effect on the next tick (machine rebuilt lazily). */
    public void stateMachine(@Nullable Id id) {
        if (Objects.equals(stateMachineId, id)) {
            return;
        }
        // machineId is part of FsmSyncKey — unbind the OLD key before it changes (else it leaks in the
        // client/server sync tables)
        if (level != null && machine != null) {
            if (level.isClientSide()) {
                FsmSyncClient.INSTANCE.unbind(syncKey());
            } else {
                SYNC_SERVER.unbind(syncKey());
            }
        }
        stateMachineId = id;
        machine = null;
        definitionWarned = false;
    }

    /** Programmatic model assignment; unbinds the previous binding, if any. */
    public void model(@Nullable ResourceLocation loc, @Nullable String name) {
        if (Objects.equals(modelLoc, loc) && Objects.equals(modelName, name)) {
            return;
        }
        unbindClientModel();
        modelLoc = loc;
        modelName = name;
        modelInstance = null;
    }

    /** The state-machine definition id this BE is bound to (persisted across world reload; {@code null} = none). */
    @Nullable
    public Id stateMachineId() {
        return stateMachineId;
    }

    //region persistence — only the programmatic ids; the machine itself rebuilds lazily from the definition

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writePersistedIds(tag, stateMachineId, modelLoc, modelName);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        PersistedIds ids = PersistedIds.read(tag);
        stateMachineId = ids.stateMachineId();
        modelLoc = ids.modelLoc();
        modelName = ids.modelName();
    }

    /** The three programmatic ids persisted to NBT. Runtime state is NOT persisted — the machine rebuilds
     *  lazily from the restored definition id. */
    public record PersistedIds(
            @Nullable Id stateMachineId,
            @Nullable ResourceLocation modelLoc,
            @Nullable String modelName
    ) {
        /** Read the persisted ids from a tag (fields absent when null at save time). */
        public static PersistedIds read(CompoundTag tag) {
            return new PersistedIds(
                    tag.contains("stateMachine") ? Id.tryParse(tag.getString("stateMachine")) : null,
                    tag.contains("modelLoc") ? ResourceLocation.tryParse(tag.getString("modelLoc")) : null,
                    tag.contains("modelName") ? tag.getString("modelName") : null);
        }
    }

    /** Write the persisted ids to a tag (omits nulls). Static + pure so it can be unit-tested without a BE. */
    public static void writePersistedIds(CompoundTag tag,
                                         @Nullable Id stateMachineId,
                                         @Nullable ResourceLocation modelLoc,
                                         @Nullable String modelName) {
        if (stateMachineId != null) {
            tag.putString("stateMachine", stateMachineId.toString());
        }
        if (modelLoc != null) {
            tag.putString("modelLoc", modelLoc.toString());
        }
        if (modelName != null) {
            tag.putString("modelName", modelName);
        }
    }

    //endregion

    //region AnimationHost — lazy machine

    /** Client model binding state — exposed for client-side inspection. */
    public boolean isModelBound() {
        return modelBound;
    }

    @Override
    public synchronized StateMachine<?> machine() {
        if (machine != null) {
            return machine;
        }
        if (stateMachineId == null) {
            return null;
        }
        StateMachineDefinition definition = FsmMachineBuilder.findDefinition(stateMachineId);
        if (definition == null) {
            if (!definitionWarned) {
                definitionWarned = true;
                LOGGER.warn("[AnimationBlockEntity] no state machine definition '{}' for BE at {}", stateMachineId, worldPosition);
            }
            return null;
        }
        PoseSink sink = isClientSide()
                ? (modelInstance != null ? new ModelInstancePoseSink(modelInstance) : null)
                : null;
        StateMachine<Object> built = FsmMachineBuilder.build(this, definition, sink);
        if (built == null) {
            return null;
        }
        if (isClientSide()) {
            // Mark logic-only client machines client-side too: the factory keys it on a non-null sink.
            built.setClientSide(true);
            // Puppet mode: the client machine does not evaluate transitions / run actions (server is
            // authoritative via conform(snapshot)); it only smooth-interpolates in-flight cross-fades.
            built.setLogicEnabled(false);
        }
        machine = built;
        definitionWarned = false;
        LOGGER.debug("[AnimationBlockEntity] machine built at {} from '{}' ({} layer(s), client={}, version={})",
                worldPosition, stateMachineId, built.layers().size(), built.isClientSide(), built.version());
        if (isClientSide()) {
            // Bind on client machine build (resets the per-key version record).
            FsmSyncClient.INSTANCE.bind(syncKey(), built);
        }
        onMachineBuilt(built);
        return built;
    }

    private boolean isClientSide() {
        return level != null && level.isClientSide();
    }

    //endregion

    //region ticking

    public void tickMachine() {
        if (level == null) {
            return;
        }
        if (level.isClientSide()) {
            tickClient();
        } else {
            tickServer();
        }
    }

    /** Server: logic-only machine, then push authoritative state to chunk-tracking players. */
    private void tickServer() {
        StateMachine<?> m = machine();
        if (m == null) {
            return;
        }
        m.tick();
        pushSync(m);
    }

    /** Client: (re)bind model → build machine with sink → tick → flush pose. Sync is applied by
     *  {@code FsmSyncClient} directly on the main thread once the machine is bound. */
    private void tickClient() {
        ensureClientModel();
        StateMachine<?> m = machine();
        if (m == null) {
            return;
        }
        m.tick();
        if (modelInstance != null) {
            modelInstance.update();
        }
        logVersionChange(m);
    }

    private void logVersionChange(StateMachine<?> m) {
        int version = m.version();
        if (version != lastLoggedVersion) {
            lastLoggedVersion = version;
            LOGGER.debug("[AnimationBlockEntity] machine at {} version -> {} (active {})",
                    worldPosition, version, m.activeStates());
        }
    }

    //endregion

    //region server sync

    private void pushSync(StateMachine<?> m) {
        if (stateMachineId == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // Recipients are players tracking this block's chunk; dedup/20-tick heartbeat live in FsmSyncServer.
        SYNC_SERVER.pushToChunkTrackers(syncKey(), m, serverLevel, worldPosition);
    }

    private void unbindServerSync() {
        if (stateMachineId == null || level == null || level.isClientSide()) {
            return;
        }
        // Remove this key's dedup and heartbeat state on machine destruction/unload.
        SYNC_SERVER.unbind(syncKey());
    }

    private FsmSyncKey syncKey() {
        // FsmSyncKey.dimension is a pure String (the uml layer is MC-free); convert the MC dimension RL here.
        String dimension = (level != null ? level.dimension().location() : Level.OVERWORLD.location()).toString();
        return new FsmSyncKey(stateMachineId, dimension, ownerDiscriminator());
    }

    //endregion

    //region sync surface (client main thread)

    /** {@code -1} until the machine is built (definitions not ready / model not bound yet). */
    public int machineVersion() {
        StateMachine<?> m = machine;
        return m == null ? -1 : m.version();
    }

    public Map<String, String> activeStates() {
        StateMachine<?> m = machine;
        return m == null ? Map.of() : m.activeStates();
    }

    /** Unique per-owner discriminator for sync keys: block position packed as a long. */
    public long ownerDiscriminator() {
        return worldPosition.asLong();
    }

    //endregion

    //region client model binding

    /**
     * Lazily binds (or self-heals) the client model. Silent when the model is not published yet —
     * the next tick retries. On a rebind (resource reload dropped the renderer entry) the machine
     * keeps its state and only its sink is swapped (model reload self-heal, machine not rebuilt).
     */
    private void ensureClientModel() {
        if (modelLoc == null || modelName == null) {
            return;
        }
        ResourceLocation instanceLoc = instanceLoc();
        if (modelBound && modelInstance != null && KasugaModelPipelines.isRendering(modelLoc, modelName, instanceLoc)) {
            return; // healthy
        }
        boolean rebinding = modelBound || modelInstance != null;
        ModelInstance fresh = KasugaModelPipelines.createAndBind(modelLoc, instanceLoc, modelName, rootTransform());
        if (fresh == null) {
            return; // model not ready yet (lazy create); retry next tick
        }
        modelInstance = fresh;
        modelBound = true;
        LOGGER.debug("[AnimationBlockEntity] model bound at {} (instance '{}', self-heal={}, isRendering={})",
                worldPosition, instanceLoc, rebinding,
                KasugaModelPipelines.isRendering(modelLoc, modelName, instanceLoc));
        StateMachine<?> m = machine;
        // Always setSink: the machine may predate the model (sink null) or need a rebind after reload.
        if (m != null) {
            m.setSink(new ModelInstancePoseSink(fresh));
            LOGGER.debug("[AnimationBlockEntity] machine sink (re-)bound at {} (version {})", worldPosition, m.version());
        }
        onClientModelBound(fresh);
    }

    /** Per-BE unique instance identifier, so two blocks never share one {@link ModelInstance}. */
    private ResourceLocation instanceLoc() {
        ResourceLocation base = modelLoc != null ? modelLoc : ResourceLocation.fromNamespaceAndPath("kasuga_lib", "unknown");
        return ResourceLocation.fromNamespaceAndPath(base.getNamespace(),
                "fsm_be_" + worldPosition.getX() + "_" + worldPosition.getY() + "_" + worldPosition.getZ());
    }

    private Transform rootTransform() {
        return new Transform().translate(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }

    private void unbindClientModel() {
        if (level == null || !level.isClientSide()) {
            return;
        }
        if (modelLoc != null && modelName != null && (modelBound || modelInstance != null)) {
            KasugaModelPipelines.unbind(modelLoc, modelName, instanceLoc());
            LOGGER.debug("[AnimationBlockEntity] model unbound at {}", worldPosition);
        }
        modelInstance = null;
        modelBound = false;
    }

    //endregion

    //region lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        if (isClientSide() && modelLoc != null && modelName != null) {
            ensureClientModel();
        }
    }

    @Override
    public void onChunkUnloaded() {
        unbindClientModel();
        unbindClientSync();
        unbindServerSync();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        unbindClientModel();
        unbindClientSync();
        unbindServerSync();
        super.setRemoved();
    }

    private void unbindClientSync() {
        if (!isClientSide() || machine == null) {
            return;
        }
        // Remove the bind entry (and its version record) on client teardown.
        FsmSyncClient.INSTANCE.unbind(syncKey());
    }

    //endregion

    //region extension points

    /** Called once when the machine is first built (both sides). */
    protected void onMachineBuilt(StateMachine<?> machine) {
    }

    /** Called whenever a model instance is (re)bound on the client. */
    protected void onClientModelBound(ModelInstance modelInstance) {
    }

    //endregion
}
