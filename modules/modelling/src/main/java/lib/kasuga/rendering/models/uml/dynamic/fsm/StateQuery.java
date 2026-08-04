package lib.kasuga.rendering.models.uml.dynamic.fsm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable, parsed path for {@link StateReader}.
 *
 * <p>Syntax is dot-separated, e.g. {@code data.player.combo}, {@code layer.upper_body.state},
 * {@code machine.tick}. The same string instance is cached with a small LRU so hot paths used
 * from scripts do not get re-parsed every tick.
 */
public final class StateQuery {

    private static final Map<String, StateQuery> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StateQuery> eldest) {
                    return size() > 256;
                }
            }
    );

    private final String path;
    private final String[] segments;
    private final int hash;

    private StateQuery(String path, String[] segments) {
        this.path = path;
        this.segments = segments;
        this.hash = Objects.hash(path);
    }

    /**
     * Parse a path. Frequently used paths are cached; the returned instance can be reused
     * across ticks for zero-allocation reads.
     */
    public static StateQuery of(String path) {
        if (path == null || path.isEmpty()) {
            return EMPTY;
        }
        StateQuery cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        String[] split = path.split("\\.");
        if (split.length == 0) {
            return EMPTY;
        }
        StateQuery query = new StateQuery(path, split);
        CACHE.put(path, query);
        return query;
    }

    private static final StateQuery EMPTY = new StateQuery("", new String[0]);

    public String path() {
        return path;
    }

    public int length() {
        return segments.length;
    }

    public boolean isEmpty() {
        return segments.length == 0;
    }

    /** First segment (the source / namespace). */
    public String source() {
        return segments.length > 0 ? segments[0] : "";
    }

    /** Segment at index, or {@code null} if out of bounds. */
    public String segment(int index) {
        return index >= 0 && index < segments.length ? segments[index] : null;
    }

    /** Join segments from {@code from} (inclusive) to the end with '.'. */
    public String subPath(int from) {
        if (from <= 0) {
            return path;
        }
        if (from >= segments.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(segments[from]);
        for (int i = from + 1; i < segments.length; i++) {
            sb.append('.').append(segments[i]);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateQuery that)) return false;
        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return path;
    }
}
