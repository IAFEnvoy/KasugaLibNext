package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.data_driven.handler.BlockEntityTypeHandler;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmBlockEntityFactories;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closes the gap between {@code BlockEntityStateMachineExtractTest} (extract, in isolation) and {@code
 * BlockEntityFactoryParamsTest} (factory, in isolation): the EXACT shape of the shipped
 * {@code kasugalib/fsm_blocks.json} entry flows through the whole chain —
 * {@link BlockEntityTypeHandler#extractEmbedded} injects the block's top-level {@code state_machine} into
 * the BE params, then the {@code fsm_be} factory consumes those params and yields a {@link BlockEntityReg}.
 * This is the data-driven block↔FSM binding contract end-to-end (short of a registry bootstrap, which the
 * game tests cover by placing the block).
 */
class FsmBlockBindingChainTest {

    @BeforeAll
    static void loadFactories() {
        // Register the FSM built-ins directly (idempotent). DataDrivenTestFactories' static registration
        // block only fires when Micronaut instantiates that @Context bean (FML runtime); in a pure-JVM gate
        // that never happens, so register here.
        FsmBlockEntityFactories.registerBuiltin();
        assertNotNull(FactoryRegistry.getBlockEntityFactory("fsm_be"), "fsm_be factory must be registered");
        assertNotNull(FactoryRegistry.get("fsm_block"), "fsm_block factory must be registered");
    }

    /** The shipped kasuga_lib:fsm_test_block entry: top-level state_machine + nested block_entity. */
    private static JsonObject shippedFsmBlock() {
        JsonObject block = new JsonObject();
        block.addProperty("id", "kasuga_lib:fsm_test_block");
        block.addProperty("type", "fsm_block");
        block.addProperty("state_machine", "kasuga_lib:fsm_test_panel");
        JsonObject be = new JsonObject();
        be.addProperty("type", "fsm_be");
        JsonObject params = new JsonObject();
        params.addProperty("model", "kasuga_lib_test:models/fsm/test_cube.obj");
        be.add("params", params);
        block.add("block_entity", be);
        return block;
    }

    @Test
    void shippedShapeExtractsThenBuildsBeRegViaFactory() {
        BlockEntityTypeHandler handler = new BlockEntityTypeHandler();
        List<JsonObject> embedded = handler.extractEmbedded(shippedFsmBlock());
        assertNotNull(embedded);
        assertEquals(1, embedded.size());

        JsonObject be = embedded.get(0);
        assertEquals("fsm_be", be.get("type").getAsString(), "handler must keep the declared block_entity type");
        JsonObject params = be.getAsJsonObject("params");
        assertEquals("kasuga_lib:fsm_test_panel", params.get("state_machine").getAsString(),
                "the top-level state_machine must land in the BE params the factory reads");
        assertEquals("kasuga_lib_test:models/fsm/test_cube.obj", params.get("model").getAsString(),
                "the block_entity.params.model must survive the extract");

        // Chain: feed the handler-produced params straight into the fsm_be factory.
        Reg<?, ?> reg = FactoryRegistry.getBlockEntityFactory("fsm_be")
                .create("fsm_chain_be", () -> new Block[0], params);
        assertNotNull(reg, "the extract→factory chain must produce a reg");
        assertTrue(reg instanceof BlockEntityReg, "fsm_be must yield a BlockEntityReg");
    }

    @Test
    void blockAndBeFactoriesComposeForShippedEntry() {
        // The shipped entry registers BOTH a block (fsm_block) and its BE (fsm_be); both must compose.
        assertNotNull(FactoryRegistry.get("fsm_block").create("kasuga_lib:fsm_test_block", null),
                "fsm_block must produce a block reg");
        JsonObject params = new JsonObject();
        params.addProperty("state_machine", "kasuga_lib:fsm_test_panel");
        Reg<?, ?> beReg = FactoryRegistry.getBlockEntityFactory("fsm_be")
                .create("kasuga_lib:fsm_test_block_be", () -> new Block[0], params);
        assertTrue(beReg instanceof BlockEntityReg, "fsm_be must yield a BlockEntityReg for the shipped BE id");
    }
}
