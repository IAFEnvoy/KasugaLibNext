package lib.kasuga.rendering.models.uml.dynamic.state_machine;

import org.jetbrains.annotations.Nullable;

public interface DataProvider {

    @Nullable Object getValue(String name);

    boolean setValue(String name, Object value);

    boolean canGet(String name);

    boolean canSet(String name, Object value);

    boolean has(String name);

    Class<?> getType(String name);
}
