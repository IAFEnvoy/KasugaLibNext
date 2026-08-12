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

    public static final ResourceLocation TEST_FAN_BE_ID =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "test_fan_be");

    public static final ResourceLocation TEST_FAN_BE_MODEL =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/be/test_fan_be.geo.json");

    public static BlockReg<TestFanBeBlock> TEST_FAN_BE_BLOCK;
    public static BlockEntityReg<KasugaBlockEntity> TEST_FAN_BE_BE;

    @PostConstruct
    public void init() {
        // 在方法内构造，规避静态字段初始化器的前向/自引用限制；
        // 注册本身由 REGISTRY 在 KasugaLibStartupEvent 时统一派发。
        TEST_FAN_BE_BE = BlockEntityReg.of(
                "test_fan_be",
                (pos, state) -> new KasugaBlockEntity(TEST_FAN_BE_BE.getEntry(), pos, state)
        ).validBlocks(Collections.singletonList((Supplier<Block>) () -> TEST_FAN_BE_BLOCK.getEntry()))
                .setParent(ModellingTestApplication.registry);

        TEST_FAN_BE_BLOCK = BlockReg.of(
                "test_fan_be",
                props -> new TestFanBeBlock(props, () -> TEST_FAN_BE_BE.getEntry())
        ).withDefaultBlockItem("test_fan_be")
                .setParent(ModellingTestApplication.registry);

        // 绑定是纯数据（RL → features/behavior），无需等方块注册完成。
        // 方块 id 固定为 kasuga_lib:test_fan_be，onLoad 时经 BuiltInRegistries 反查。
        PipelineBindingRegistry.registerBlock(TEST_FAN_BE_ID, BlockPipelineBinding.single(
                TEST_FAN_BE_MODEL,
                null
        ));
    }
}
