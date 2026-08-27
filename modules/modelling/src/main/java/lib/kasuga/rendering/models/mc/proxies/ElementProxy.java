package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.state_machine.DataProvider;
import org.jetbrains.annotations.Nullable;

public interface ElementProxy<ProxiedType, ProxiedInstanceType> {

    boolean isValidInput(Object input);

    boolean isValidInstance(Object instance);

    @Nullable DataProvider getDataProvider(ProxiedInstanceType instance, Object... externalData);
}
