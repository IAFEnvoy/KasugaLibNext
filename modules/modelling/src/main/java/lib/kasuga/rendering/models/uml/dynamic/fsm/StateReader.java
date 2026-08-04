package lib.kasuga.rendering.models.uml.dynamic.fsm;

/**
 * Unified, read-only view over a state machine and its collaborating objects
 * ({@link StateMachine}, {@link Layer}, {@link State}, {@link lib.kasuga.rendering.models.uml.dynamic.data.Blackboard}).
 *
 * <p>Path syntax:
 * <ul>
 *   <li>{@code owner} — the owning actor.</li>
 *   <li>{@code machine.tick}, {@code machine.version}, {@code machine.client}.</li>
 *   <li>{@code layer.<id>.state} — active state id of a layer.</li>
 *   <li>{@code layer.<id>.mode}, {@code layer.<id>.weight}, {@code layer.<id>.locked}.</li>
 *   <li>{@code state.id}, {@code state.elapsed}, {@code state.duration}.</li>
 *   <li>{@code data.<key>} — raw blackboard value (key may contain dots).</li>
 *   <li>{@code signal.<key>} — machine signal.</li>
 *   <li>{@code trigger.<key>} — boolean, true if trigger set this tick.</li>
 * </ul>
 */
public interface StateReader {

    /** Returns {@code true} if the queried path is supported and currently has a value. */
    boolean has(StateQuery query);

    /** Read the value at the given path, or {@code null} if missing / unsupported. */
    Object read(StateQuery query);

    /** Read and cast; returns {@code null} if missing or not assignable. */
    default <T> T read(StateQuery query, Class<T> type) {
        Object value = read(query);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

    /** Read with a fallback. */
    default <T> T read(StateQuery query, T defaultValue) {
        T value = read(query, (Class<T>) defaultValue.getClass());
        return value != null ? value : defaultValue;
    }

    default Object read(String path) {
        return read(StateQuery.of(path));
    }

    default <T> T read(String path, Class<T> type) {
        return read(StateQuery.of(path), type);
    }

    default <T> T read(String path, T defaultValue) {
        return read(StateQuery.of(path), defaultValue);
    }

    default boolean readBool(String path) {
        Object value = read(path);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    default int readInt(String path) {
        Object value = read(path);
        return value instanceof Number n ? n.intValue() : 0;
    }

    default float readFloat(String path) {
        Object value = read(path);
        return value instanceof Number n ? n.floatValue() : 0f;
    }

    default String readString(String path) {
        Object value = read(path);
        return value == null ? "" : String.valueOf(value);
    }
}
