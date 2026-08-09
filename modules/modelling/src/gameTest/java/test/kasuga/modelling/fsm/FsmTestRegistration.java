package test.kasuga.modelling.fsm;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.micronaut.context.annotation.Context;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.rendering.models.uml.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmBlock;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachineDefinitionLoader;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Collection;

/**
 * 冒烟测试注册（modelling 的 contentTesting）：在 mod 构造期直接注册测试方块
 * （{@code kasuga_lib:fsm_test_block} + fsm_test_block_be 方块实体）并把状态机定义注册进
 * {@link FsmRegistries#GLOBAL} 的定义桶——等效 data-driven 的 JSON 注册链，但自包含
 * （modelling 的 dedicated-server run 不依赖 data-driven 模块，JSON 树不存在，
 * 因此不能走 FactoryRegistry 工厂链，必须直接挂注册树）。
 *
 * <p>另注册一组「强类型状态变量」fixture：把 {@link StateVar}（speed/armed/attack）、一个读取 speed 的
 * 条件、以及一个声明 {@code state_vars} + 触发器过渡的定义注册进全局表，供 {@link FsmTypedStateGameTest}
 * 做服务端集成验证。
 *
 * <p>挂树时机：{@code KasugaLibApplication.REGISTRY} 是 static final（类加载即就绪），
 * 本 bean 在 mod 构造期实例化时 addChild，先于 {@code REGISTRY.register(modEventBus)}
 * 的 dispatch——与 data-driven 的 JsonRegistryGroup 挂载时序一致。
 */
@Context
public final class FsmTestRegistration {

    public static final ResourceLocation MACHINE_ID = ResourceLocation.parse("kasuga_lib:fsm_test_panel");

    /** Strongly-typed state vars for the typed-state fixture, registered into the shared {@link StateVarRegistry}
     * ({@link FsmRegistries#GLOBAL}). */
    public static final StateVar<Float> SPEED = StateVar.of(
            ResourceLocation.parse("kasuga_lib:fsm_test_typed/speed"), Float.class, Codec.FLOAT, 0f);
    public static final StateVar<Boolean> ARMED = StateVar.of(
            ResourceLocation.parse("kasuga_lib:fsm_test_typed/armed"), Boolean.class, Codec.BOOL, false);
    public static final StateVar<Boolean> ATTACK = StateVar.builder(
                    ResourceLocation.parse("kasuga_lib:fsm_test_typed/attack"), Boolean.class, Codec.BOOL)
            .defaultValue(Boolean.FALSE)
            .ephemeral()
            .build();

    /** Written by the on_enter action on the "moving" state — proves the full condition→action→var chain runs. */
    public static final StateVar<Boolean> ACTION_RAN = StateVar.of(
            ResourceLocation.parse("kasuga_lib:fsm_test_typed/action_ran"), Boolean.class, Codec.BOOL, false);

    /** A second machine id whose definition declares {@code state_vars} + a trigger transition. */
    public static final ResourceLocation TYPED_MACHINE_ID = ResourceLocation.parse("kasuga_lib:fsm_test_typed");

    // === Complex fixture: multi-layer locomotion + combat ===

    public static final StateVar<Boolean> JUMP = StateVar.trigger(
            ResourceLocation.parse("kasuga_lib:fsm_test_complex/jump"));
    public static final StateVar<Boolean> ATTACK_TRIGGER = StateVar.trigger(
            ResourceLocation.parse("kasuga_lib:fsm_test_complex/attack"));
    public static final StateVar<Integer> HIT_COUNT = StateVar.of(
            ResourceLocation.parse("kasuga_lib:fsm_test_complex/hit_count"), Integer.class, Codec.INT, 0);
    public static final StateVar<Boolean> COMBAT_LOCK = StateVar.of(
            ResourceLocation.parse("kasuga_lib:fsm_test_complex/combat_lock"), Boolean.class, Codec.BOOL, false);
    public static final ResourceLocation COMPLEX_MACHINE_ID =
            ResourceLocation.parse("kasuga_lib:fsm_test_complex");

    /**
     * A machine id whose definition is provided ONLY by the data-driven {@link StateMachineDefinitionLoader}
     * reading {@code data/kasuga_lib/state_machines/gametest_loader.json} (a gameTest resource) — it is NOT
     * inline-registered here. Used by {@code FsmDataDrivenGameTest} to prove the loader→registry→block→BE path
     * runs end-to-end in the real dedicated-server runtime.
     */
    public static final ResourceLocation LOADER_MACHINE_ID =
            ResourceLocation.parse("kasuga_lib:gametest_loader");

    private static final String DEFINITION_JSON = """
            {
              "id": "kasuga_lib:fsm_test_panel",
              "layers": [
                {
                  "id": "base", "mode": "base", "weight": 1.0, "bone_mask": "*",
                  "initial_state": "idle",
                  "states": [
                    { "id": "idle", "duration_ticks": 40,
                      "pose": { "bones": [ { "name": "cube",
                        "transform": { "translate": [0, 0, 0] }, "mode": "replace" } ] } },
                    { "id": "active", "duration_ticks": 20,
                      "pose": { "bones": [ { "name": "cube",
                        "transform": { "translate": [0, 1, 0] }, "mode": "replace" } ] } }
                  ],
                  "transitions": [
                    { "id": "idle_to_active", "from": "idle", "to": "active", "when_complete": true },
                    { "id": "active_to_idle", "from": "active", "to": "idle", "when_complete": true }
                  ]
                }
              ]
            }
            """;

    /**
     * Typed fixture definition: declares {@code state_vars} by reference (so the factory reuses the Java
     * constants above) and exercises both a var-driven condition transition and an ephemeral trigger transition.
     */
    private static final String TYPED_DEFINITION_JSON = """
            {
              "id": "kasuga_lib:fsm_test_typed",
              "state_vars": [
                { "name": "speed", "reference": "kasuga_lib:fsm_test_typed/speed" },
                { "name": "armed", "reference": "kasuga_lib:fsm_test_typed/armed" },
                { "name": "attack", "reference": "kasuga_lib:fsm_test_typed/attack" },
                { "name": "action_ran", "reference": "kasuga_lib:fsm_test_typed/action_ran" }
              ],
              "layers": [
                {
                  "id": "base", "mode": "base", "weight": 1.0,
                  "initial_state": "idle",
                  "states": [
                    { "id": "idle" },
                    { "id": "moving", "duration_ticks": 10,
                      "on_enter": ["kasuga_lib:fsm_test/record_active"] },
                    { "id": "strike", "duration_ticks": 5 }
                  ],
                  "transitions": [
                    { "id": "idle_to_moving", "from": "idle", "to": "moving", "when": ["kasuga_lib:fsm_test/is_moving"] },
                    { "id": "moving_to_idle", "from": "moving", "to": "idle", "when_complete": true },
                    { "id": "idle_to_strike", "from": "idle", "to": "strike", "trigger_on": "attack" },
                    { "id": "strike_to_idle", "from": "strike", "to": "idle", "when_complete": true }
                  ]
                }
              ]
            }
            """;

    static {
        registerDefinition();
        registerTypedFixture();
        registerComplexFixture();
        registerTestBlock();
        registerTypedTestBlock();
        registerComplexTestBlock();
        registerLoaderTestBlock();
    }

    /** Micronaut instantiates this @Context bean during mod construction — must be accessible. */
    public FsmTestRegistration() {
    }

    private static void registerDefinition() {
        StateMachineDefinition definition = StateMachineDefinition.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString(DEFINITION_JSON))
                .resultOrPartial(error -> {
                    throw new IllegalStateException("fsm test definition decode failed: " + error);
                })
                .orElseThrow().getFirst();
        FsmRegistries.GLOBAL.definitions().register(MACHINE_ID, definition);
    }

    /** Typed-state fixture: vars + a var-reading condition + an on_enter action + the typed definition. */
    private static void registerTypedFixture() {
        FsmRegistries.GLOBAL.vars().register(SPEED);
        FsmRegistries.GLOBAL.vars().register(ARMED);
        FsmRegistries.GLOBAL.vars().register(ATTACK);
        FsmRegistries.GLOBAL.vars().register(ACTION_RAN);
        FsmRegistries.GLOBAL.functions().registerAction(
                ResourceLocation.parse("kasuga_lib:fsm_test/record_active"),
                ctx -> ctx.set(ACTION_RAN, true));
        FsmRegistries.GLOBAL.functions().registerCondition(
                ResourceLocation.parse("kasuga_lib:fsm_test/is_moving"),
                ctx -> ctx.get(SPEED) > 0f);
        StateMachineDefinition typed = StateMachineDefinition.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString(TYPED_DEFINITION_JSON))
                .resultOrPartial(error -> {
                    throw new IllegalStateException("typed fsm test definition decode failed: " + error);
                })
                .orElseThrow().getFirst();
        FsmRegistries.GLOBAL.definitions().register(TYPED_MACHINE_ID, typed);
    }

    /** Directly registers the block, its block entity and the machine binding on the registry tree. */
    @SuppressWarnings("unchecked")
    private static void registerTestBlock() {
        registerFsmBlock("fsm_test_block", "fsm_test_block_be", MACHINE_ID,
                ResourceLocation.parse("kasuga_lib:models/fsm/test_cube.obj"), "cube");
    }

    /** Server-only test block whose BE binds to the typed-state machine (no model needed for logic tests). */
    @SuppressWarnings("unchecked")
    private static void registerTypedTestBlock() {
        registerFsmBlock("fsm_test_typed_block", "fsm_test_typed_block_be", TYPED_MACHINE_ID, null, null);
    }

    /** Server-only test block for the complex multi-layer machine. */
    @SuppressWarnings("unchecked")
    private static void registerComplexTestBlock() {
        registerFsmBlock("fsm_test_complex_block", "fsm_test_complex_block_be", COMPLEX_MACHINE_ID, null, null);
    }

    /**
     * Server-only test block bound to a definition the {@link StateMachineDefinitionLoader} loads from a gameTest
     * resource ({@code state_machines/gametest_loader.json}). NOT inline-registered — the definition reaches the
     * registry only via the real loader path at server boot.
     */
    @SuppressWarnings("unchecked")
    private static void registerLoaderTestBlock() {
        registerFsmBlock("fsm_test_loader_block", "fsm_test_loader_block_be", LOADER_MACHINE_ID, null, null);
    }

    /**
     * Complex multi-layer machine:
     * <p>Layer "locomotion" (BASE): idle ↔ walk ↔ run (speed-driven), jump trigger → air → land → idle.
     * <p>Layer "combat" (OVERRIDE): none → windup → hit → recover → none (attack trigger chain),
     * each combat state writes HIT_COUNT; windup sets COMBAT_LOCK (locks locomotion during combat).
     */
    private static void registerComplexFixture() {
        FsmRegistries.GLOBAL.vars().register(JUMP);
        FsmRegistries.GLOBAL.vars().register(ATTACK_TRIGGER);
        FsmRegistries.GLOBAL.vars().register(HIT_COUNT);
        FsmRegistries.GLOBAL.vars().register(COMBAT_LOCK);
        // re-use SPEED from the typed fixture

        FsmRegistries.GLOBAL.functions().registerCondition(
                ResourceLocation.parse("kasuga_lib:fsm_test/is_walking"),
                ctx -> ctx.get(SPEED) > 0f && ctx.get(SPEED) <= 5f);
        FsmRegistries.GLOBAL.functions().registerCondition(
                ResourceLocation.parse("kasuga_lib:fsm_test/is_running"),
                ctx -> ctx.get(SPEED) > 5f);
        FsmRegistries.GLOBAL.functions().registerCondition(
                ResourceLocation.parse("kasuga_lib:fsm_test/is_standing"),
                ctx -> ctx.get(SPEED) <= 0f);
        FsmRegistries.GLOBAL.functions().registerCondition(
                ResourceLocation.parse("kasuga_lib:fsm_test/combat_active"),
                ctx -> ctx.get(COMBAT_LOCK));
        FsmRegistries.GLOBAL.functions().registerAction(
                ResourceLocation.parse("kasuga_lib:fsm_test/start_combat_lock"),
                ctx -> {
                    ctx.set(COMBAT_LOCK, true);
                    ctx.lockLayer("locomotion", 30);
                });
        FsmRegistries.GLOBAL.functions().registerAction(
                ResourceLocation.parse("kasuga_lib:fsm_test/end_combat_lock"),
                ctx -> {
                    ctx.set(COMBAT_LOCK, false);
                    ctx.unlockLayer("locomotion");
                });
        FsmRegistries.GLOBAL.functions().registerAction(
                ResourceLocation.parse("kasuga_lib:fsm_test/record_hit"),
                ctx -> ctx.set(HIT_COUNT, ctx.get(HIT_COUNT) + 1));

        String complexJson = """
                {
                  "id": "kasuga_lib:fsm_test_complex",
                  "state_vars": [
                    { "name": "speed", "reference": "kasuga_lib:fsm_test_typed/speed" },
                    { "name": "jump", "reference": "kasuga_lib:fsm_test_complex/jump" },
                    { "name": "attack", "reference": "kasuga_lib:fsm_test_complex/attack" },
                    { "name": "hit_count", "reference": "kasuga_lib:fsm_test_complex/hit_count" },
                    { "name": "combat_lock", "reference": "kasuga_lib:fsm_test_complex/combat_lock" }
                  ],
                  "layers": [
                    {
                      "id": "locomotion", "mode": "base", "weight": 1.0,
                      "initial_state": "idle",
                      "states": [
                        { "id": "idle" },
                        { "id": "walk" },
                        { "id": "run" },
                        { "id": "air", "duration_ticks": 5 },
                        { "id": "land", "duration_ticks": 3 }
                      ],
                      "transitions": [
                        { "id": "idle_to_walk", "from": "idle", "to": "walk",
                          "when": ["kasuga_lib:fsm_test/is_walking"] },
                        { "id": "walk_to_run", "from": "walk", "to": "run",
                          "when": ["kasuga_lib:fsm_test/is_running"] },
                        { "id": "run_to_walk", "from": "run", "to": "walk",
                          "when": ["kasuga_lib:fsm_test/is_walking"] },
                        { "id": "walk_to_idle", "from": "walk", "to": "idle",
                          "when": ["kasuga_lib:fsm_test/is_standing"] },
                        { "id": "run_to_idle", "from": "run", "to": "idle",
                          "when": ["kasuga_lib:fsm_test/is_standing"] },
                        { "id": "idle_to_air", "from": "idle", "to": "air", "trigger_on": "jump" },
                        { "id": "walk_to_air", "from": "walk", "to": "air", "trigger_on": "jump" },
                        { "id": "air_to_land", "from": "air", "to": "land", "when_complete": true },
                        { "id": "land_to_idle", "from": "land", "to": "idle", "when_complete": true }
                      ]
                    },
                    {
                      "id": "combat", "mode": "override", "weight": 1.0,
                      "initial_state": "none",
                      "states": [
                        { "id": "none" },
                        { "id": "windup", "duration_ticks": 3,
                          "on_enter": ["kasuga_lib:fsm_test/start_combat_lock"] },
                        { "id": "hit", "duration_ticks": 2,
                          "on_enter": ["kasuga_lib:fsm_test/record_hit"] },
                        { "id": "recover", "duration_ticks": 5,
                          "on_exit": ["kasuga_lib:fsm_test/end_combat_lock"] }
                      ],
                      "transitions": [
                        { "id": "start_attack", "from": "none", "to": "windup", "trigger_on": "attack" },
                        { "id": "windup_to_hit", "from": "windup", "to": "hit", "when_complete": true },
                        { "id": "hit_to_recover", "from": "hit", "to": "recover", "when_complete": true },
                        { "id": "recover_to_none", "from": "recover", "to": "none", "when_complete": true }
                      ]
                    }
                  ]
                }
                """;
        StateMachineDefinition complex = StateMachineDefinition.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString(complexJson))
                .resultOrPartial(error -> {
                    throw new IllegalStateException("complex fsm test definition decode failed: " + error);
                })
                .orElseThrow().getFirst();
        FsmRegistries.GLOBAL.definitions().register(COMPLEX_MACHINE_ID, complex);
    }

    /**
     * Registers an {@link FsmBlock} + its {@link AnimationBlockEntity} type bound to {@code machineId}, on the
     * registry tree. {@code modelLoc/modelName} attach a client render binding (null = logic-only server test).
     */
    @SuppressWarnings("unchecked")
    private static void registerFsmBlock(
            String blockName,
            String beName,
            ResourceLocation machineId,
            ResourceLocation modelLoc,
            String modelName
    ) {
        Reg<?, Block> blockReg = (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(blockName, FsmBlock::new)
                .withDefaultBlockItem(blockName);
        BlockEntityReg<AnimationBlockEntity> beReg = new BlockEntityReg<>(beName,
                r -> (pos, state) -> new AnimationBlockEntity(r.getEntry(), pos, state, machineId, modelLoc, modelName));
        // validBlocks 经 property 注入（注册时求值，此时 blockReg 已注册）——否则 BE 类型
        // isValid 恒 false，FsmBlock.newBlockEntity 扫描不到它。
        beReg.withProperty(Collection.class,
                col -> {
                    col.addAll(java.util.List.of(blockReg.getEntry()));
                    return col;
                });
        blockReg.addChild(beReg);
        KasugaLibApplication.REGISTRY.addChild(blockReg);
    }
}
