package lib.kasuga.mixins;

import lib.kasuga.inject.mixins.ServiceMixinPlugin;
import org.spongepowered.asm.service.MixinService;
import java.util.List;

public class KasugaLibMixinPlugin extends ServiceMixinPlugin {
    @Override
    public List<String> getMixins() {
        if (org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().getSide()
                != org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT) return List.of();
        // Supply optional classes through the plugin contract; adding a new
        // config from onLoad mutates MixinProcessor's active config iterator.
        String marker = "lib/kasuga/mixins/modelling/PeelShaderLoaderMixin.class";
        try (var resource = MixinService.getService().getResourceAsStream(marker)) {
            return resource == null ? List.of() : List.of(
                    "modelling.PeelShaderLoaderMixin",
                    "modelling.PeelChunkRendererMixin",
                    "modelling.PeelWorldRendererMixin");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot inspect optional modelling mixins", exception);
        }
    }
}
