package test.kasuga.modelling;

import io.micronaut.context.annotation.Context;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabReg;
import lib.kasuga.registration.minecraft.creative_tab.CreativeTabRegModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@Context
public class ModellingTestCreativeTab {

    public static CreativeTabReg MODELLING_TEST_TAB = new CreativeTabReg("modelling_test")
            .configure(CreativeTabRegModifiers.TabBuilder.of("title",
                builder -> builder.title(Component.translatable("itemGroup.kasuga_lib.modelling_test"))))
            .configure(CreativeTabRegModifiers.TabBuilder.of("icon",
                builder -> builder.icon(() -> new ItemStack(Items.COMMAND_BLOCK))))
            .setParent(ModellingTestApplication.registry);
}
