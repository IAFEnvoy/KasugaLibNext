package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Layer;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * v2.0 fan test block entity (standard FSM): gear = state of the {@code "gear"} layer, exactly one
 * external trigger ({@link #CYCLE}), authoritative logic machine on the server, puppet + render
 * projection on the client. Acceleration/angle are derived per-instance by {@link FanVarProvider} on the
 * client animation driver — nothing domain-specific crosses the sync channel.
 *
 * <p>Persistence: only the gear index (byte 0..3) is saved; on load the machine is rebuilt lazily from
 * the registered definition and {@link #onMachineBuilt} imperatively restores the layer to the persisted
 * state. {@code onMachineBuilt} fires on both sides — the server restores the authoritative state, the
 * client puppet's {@code goTo} is a no-op (puppets ignore pendingGoTo) and is overridden by the FSM sync
 * snapshot conform, so the client is safe.
 */
public class FanBlockEntity extends AnimationBlockEntity {

    /** The state machine definition id ({@code kasuga_lib:fan_machine}). */
    public static final Id FAN_MACHINE_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan_machine");

    /** The .bbmodel geometry for this block ({@code models/be/test_fan_be.bbmodel}). */
    public static final ResourceLocation FAN_MODEL_LOC =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/be/test_fan_be.bbmodel");

    /** Layer state ids in active-index order — index = gear 0..3. */
    public static final String[] GEAR_STATE_IDS = {"off", "g1", "g2", "g3"};

    /** The single external trigger (right-click): cycles {@code off→g1→g2→g3→off}. Registered into
     * {@code FsmRegistries.GLOBAL.vars()} by {@link ModellingContentTest#init()}; the machine definition
     * references it as {@code kasuga_lib:fan/cycle}. */
    public static final StateVar<Boolean> CYCLE = StateVar.trigger(Id.fromNamespaceAndPath("kasuga_lib", "fan/cycle"));

    /** Persisted gear index pending machine build ({@code -1} = none / already consumed). */
    private int pendingGear = -1;

    public FanBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, FAN_MACHINE_ID, FAN_MODEL_LOC, null);
        setVarProvider(new FanVarProvider());
    }

    //region persistence — only the gear index (byte 0..3)

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("gear", (byte) currentGearIndex());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pendingGear = tag.getByte("gear");
    }

    @Override
    protected void onMachineBuilt(StateMachine<?> machine) {
        super.onMachineBuilt(machine);
        if (pendingGear >= 0 && pendingGear <= 3) {
            machine.goTo("gear", GEAR_STATE_IDS[pendingGear]);
            pendingGear = -1;
        }
    }

    /**
     * Active gear index (0..3) without building the machine: {@code machineVersion() < 0} means the machine
     * has not been built yet → write 0 (the machine starts at {@code off} by definition).
     */
    private int currentGearIndex() {
        if (machineVersion() < 0) {
            return 0;
        }
        StateMachine<?> machine = machine();
        if (machine == null) {
            return 0;
        }
        Layer<?> gear = machine.layerOrNull("gear");
        if (gear == null) {
            return 0;
        }
        int index = gear.activeStateIndex();
        return index >= 0 ? index : 0;
    }

    //endregion
}