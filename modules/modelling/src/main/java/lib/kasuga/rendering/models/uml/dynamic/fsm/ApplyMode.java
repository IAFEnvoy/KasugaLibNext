package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** How a bone-channel target is applied when flushing a blended pose to the skeleton. */
public enum ApplyMode implements StringRepresentable {
    /** Replace the bone's transform entirely. */
    REPLACE("replace"),
    /** Multiply onto the current transform (additive bone delta). */
    MULTIPLY("multiply"),
    /** Add a translation delta onto the current transform. */
    ADD("add");

    public static final Codec<ApplyMode> CODEC = StringRepresentable.fromEnum(ApplyMode::values);

    private final String name;

    ApplyMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static ApplyMode byName(String name) {
        if (name == null) {
            return REPLACE;
        }
        for (ApplyMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return REPLACE;
    }
}
