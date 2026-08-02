package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.content.block.KasugaBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.RenderShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class TestJeFanBlock extends Block implements EntityBlock {

    private final Supplier<BlockEntityType<?>> blockEntityType;

    public TestJeFanBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {
        super(properties);
        this.blockEntityType = blockEntityType;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KasugaBlockEntity(blockEntityType.get(), pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }
}
