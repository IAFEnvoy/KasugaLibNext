package test.kasuga.modelling;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.rendering.models.mc.content.block.KasugaBlockEntity;
import lib.kasuga.rendering.models.mc.registry.PipelineBindingRegistry;
import lib.kasuga.rendering.models.mc.registry.pipeline_binding.BlockPipelineBinding;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.function.Supplier;

@Context
public class ModellingContentTest {

    public static final ResourceLocation TEST_JE_FAN_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_je_fan");

    public static final ResourceLocation TEST_JE_FAN_MODEL =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/je/test_je_fan.json");

    public static BlockReg<TestJeFanBlock> TEST_JE_FAN_BLOCK;
    public static BlockEntityReg<KasugaBlockEntity> TEST_JE_FAN_BE;

    @PostConstruct
    public void init() {
        // 在方法内构造，规避静态字段初始化器的前向/自引用限制；
        // 注册本身由 REGISTRY 在 KasugaLibStartupEvent 时统一派发。
        TEST_JE_FAN_BE = BlockEntityReg.of(
                "test_je_fan",
                (pos, state) -> new KasugaBlockEntity(TEST_JE_FAN_BE.getEntry(), pos, state)
        ).validBlocks(Collections.singletonList((Supplier<Block>) () -> TEST_JE_FAN_BLOCK.getEntry()))
                .setParent(ModellingTestApplication.registry);

        TEST_JE_FAN_BLOCK = BlockReg.of(
                "test_je_fan",
                props -> new TestJeFanBlock(props, () -> TEST_JE_FAN_BE.getEntry())
        ).withDefaultBlockItem("test_je_fan")
                .setParent(ModellingTestApplication.registry);

        // 绑定是纯数据（RL → features/behavior），无需等方块注册完成。
        // 方块 id 固定为 kasuga_lib:test_je_fan，onLoad 时经 BuiltInRegistries 反查。
        PipelineBindingRegistry.registerBlock(TEST_JE_FAN_ID, BlockPipelineBinding.single(
                TEST_JE_FAN_MODEL,
                (state, pos) -> new Transform().translate(pos.getX(), pos.getY(), pos.getZ()),
                null
        ));
    }
}
