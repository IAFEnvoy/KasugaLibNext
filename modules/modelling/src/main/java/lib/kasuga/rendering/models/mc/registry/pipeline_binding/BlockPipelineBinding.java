package lib.kasuga.rendering.models.mc.registry.pipeline_binding;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.registry.RenderBehavior;
import lib.kasuga.rendering.models.mc.registry.RenderFeature;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BiFunction;

public record BlockPipelineBinding(
        List<RenderFeature> features,
        @Nullable RenderBehavior behavior
) {

    public static BlockPipelineBinding single(
            ResourceLocation modelKey,
            BiFunction<BlockState, BlockPos, Transform> rootTransform,
            @Nullable RenderBehavior behavior
    ) {
        return new BlockPipelineBinding(List.of(new RenderFeature("main", modelKey, rootTransform)), behavior);
    }

    public static BlockPipelineBinding composite(
            List<RenderFeature> features,
            @Nullable RenderBehavior behavior
    ) {
        return new BlockPipelineBinding(features, behavior);
    }

    public ResourceLocation featureInstanceKey(BlockPos pos, ResourceKey<Level> dimension, String featureId) {
        String dim = dimension.location().toString().replace(':', '/');
        return ResourceLocation.tryBuild(
                KasugaLib.MODID,
                "instance/%s/%d/%d/%d/%s".formatted(dim, pos.getX(), pos.getY(), pos.getZ(), featureId)
        );
    }
}
