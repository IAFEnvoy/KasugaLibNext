package lib.kasuga.rendering.models.uml.dynamic.fsm.state;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Strongly-typed state variable — the FSM analog of Minecraft's {@code DataComponentType<T>}. Each var
 * bundles its value type {@code T}, a {@link Codec} (serialization / data-driven default), a default value,
 * an optional validator, an optional {@link StreamCodec} (reserved for a future network-sync layer), and an
 * {@code ephemeral} flag (tick-scoped: cleared at the end of every tick, used for triggers).
 *
 * <p>Identity is by {@link ResourceLocation} <b>alone</b>: two vars with the same id are equal, so a var
 * works as a {@link java.util.HashMap} key and resolves by id from JSON/scripts. The value type {@code T} is
 * erased at runtime; type safety is by construction — {@link MutableStateMap#set} only stores a value of the
 * var's own type and throws {@link IllegalStateException} on a same-id-different-type collision, so
 * {@link StateMap#get} is type-safe.
 *
 * <p>Build with {@link #builder(ResourceLocation, Class, Codec)} (mirrors {@code DataComponentType.Builder})
 * or the {@link #of} shortcut.
 */
public final class StateVar<T> {

    private final ResourceLocation id;
    private final Class<T> type;
    private final Codec<T> codec;
    private final T defaultValue;
    private final Predicate<? super T> validator;
    private final StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec;
    private final boolean ephemeral;

    private StateVar(
            ResourceLocation id,
            Class<T> type,
            Codec<T> codec,
            T defaultValue,
            Predicate<? super T> validator,
            StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec,
            boolean ephemeral
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.validator = validator;
        this.streamCodec = streamCodec;
        this.ephemeral = ephemeral;
        if (!isValid(defaultValue)) {
            throw new IllegalArgumentException(
                    "default value for " + id + " fails its validator: " + defaultValue);
        }
    }

    public ResourceLocation id() {
        return id;
    }

    public Class<T> type() {
        return type;
    }

    public Codec<T> codec() {
        return codec;
    }

    public T defaultValue() {
        return defaultValue;
    }

    /**
     * The default value, defensively copied for mutable value types (e.g. {@link Vector3f}) so a caller
     * mutating the returned default cannot poison other machines that share it. Immutable types return the
     * shared default instance directly.
     */
    @SuppressWarnings("unchecked")
    public T copyOfDefault() {
        if (defaultValue instanceof Vector3f v) {
            return (T) new Vector3f(v);
        }
        return defaultValue;
    }

    public boolean ephemeral() {
        return ephemeral;
    }

    /** True iff {@code value} is non-null and passes this var's validator. */
    public boolean isValid(T value) {
        return value != null && (validator == null || validator.test(value));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StateVar<?> that)) {
            return false;
        }
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "StateVar[" + id + "]";
    }

    public static <T> Builder<T> builder(ResourceLocation id, Class<T> type, Codec<T> codec) {
        return new Builder<>(id, type, codec);
    }

    public static <T> StateVar<T> of(ResourceLocation id, Class<T> type, Codec<T> codec, T defaultValue) {
        return builder(id, type, codec).defaultValue(defaultValue).build();
    }

    /**
     * Tick-scoped boolean trigger: ephemeral, default {@code false}. Cleared at the end of every tick by
     * {@link MutableStateMap#removeEphemeral()}. Use with {@code Transition.on(trigger)} /
     * {@code StateMachine.trigger(var)}. For a latched (buffered) trigger use a <em>non-ephemeral</em> bool
     * var with {@code Transition.onBuffered(var)} + {@code StateMachine.triggerBuffered(var)}.
     */
    public static StateVar<Boolean> trigger(ResourceLocation id) {
        return builder(id, Boolean.class, Codec.BOOL).defaultValue(Boolean.FALSE).ephemeral().build();
    }

    /** Fluent builder, mirroring {@code DataComponentType.Builder}. */
    public static final class Builder<T> {

        private final ResourceLocation id;
        private final Class<T> type;
        private final Codec<T> codec;
        private T defaultValue;
        private Predicate<? super T> validator;
        private StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec;
        private boolean ephemeral;

        private Builder(ResourceLocation id, Class<T> type, Codec<T> codec) {
            this.id = id;
            this.type = type;
            this.codec = codec;
        }

        public Builder<T> defaultValue(T value) {
            this.defaultValue = value;
            return this;
        }

        public Builder<T> validator(Predicate<? super T> validator) {
            this.validator = validator;
            return this;
        }

        /**
         * <b>Reserved</b> for a future network-sync layer. Setting this has <b>no effect today</b> — no code
         * consumes it; it is kept so that adding var sync later is a single builder call here.
         */
        public Builder<T> streamCodec(StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec) {
            this.streamCodec = streamCodec;
            return this;
        }

        /** Mark this var tick-scoped: cleared at the end of every tick (use for triggers). */
        public Builder<T> ephemeral() {
            this.ephemeral = true;
            return this;
        }

        public StateVar<T> build() {
            return new StateVar<>(id, type, codec, defaultValue, validator, streamCodec, ephemeral);
        }
    }
}
