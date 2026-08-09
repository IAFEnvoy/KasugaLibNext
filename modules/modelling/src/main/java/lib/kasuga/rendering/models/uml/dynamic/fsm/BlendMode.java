package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How a {@link Layer} composes with the layers below it. Distinct axis from
 * {@code MorphInstance.BlendMode} (per-morph color multiply/add).
 */
public enum BlendMode implements StringRepresentable {
    /** Bottom layer; its pose is the base. */
    BASE("base"),
    /** Accumulates (adds) on top of the base within the {@link Blender}. */
    ADDITIVE("additive"),
    /** Masked replace: overrides base+additive for the masked channels. */
    OVERRIDE("override");

    public static final Codec<BlendMode> CODEC = StringRepresentable.fromEnum(BlendMode::values);

    private final String name;

    BlendMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
