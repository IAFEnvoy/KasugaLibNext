package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.data.Blackboard;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Owner-generic animation state machine. The whole API is parameterized by {@link Owner}, so
 * {@code ctx.owner()} is fully typed (no casts). Built via the lambda DSL:
 *
 * <pre>{@code
 * StateMachine<MyActor> machine = StateMachine.<MyActor>builder(actor)
 *         .layer("locomotion", layer -> { ... })
 *         .layer("upper_body", layer -> { ... })
 *         .build();
 * machine.tick();
 * }</pre>
 *
 * <p>Layers run in parallel (orthogonality); each tick composes their poses (BASE/ADDITIVE/OVERRIDE)
 * into a {@link Blender} and flushes via the {@link PoseSink} (null on a logic-only server).
 */
public final class StateMachine<Owner> implements StateReader {

    private final Owner owner;
    private final List<Layer<Owner>> layers = new ArrayList<>();
    private final Map<String, Layer<Owner>> layersById = new HashMap<>();
    private PoseSink sink;
    private boolean clientSide;
    private int version;
    private long tickCount;

    private final Map<String, Boolean> triggers = new HashMap<>();
    private final Blackboard data = Blackboard.empty();
    private final Map<String, Integer> locks = new HashMap<>();

    private StateMachine(Owner owner) {
        this.owner = owner;
    }

    public static <O> Builder<O> builder(O owner) {
        return new Builder<>(owner);
    }

    //region api

    public Owner owner() {
        return owner;
    }

    public List<Layer<Owner>> layers() {
        return layers;
    }

    /**
     * Look up a layer by id; non-null by contract &mdash; throws {@link NoSuchElementException} if no layer
     * has that id (fail-fast on programmer error). For lookups driven by external input (scripting /
     * JSON), use {@link #layerOrNull(String)}.
     */
    public Layer<Owner> layer(String id) {
        Layer<Owner> layer = layerOrNull(id);
        if (layer == null) {
            throw new NoSuchElementException("layer not found: " + id);
        }
        return layer;
    }

    /** Nullable layer lookup for best-effort callers (scripting / JSON); {@code null} if the id is unknown. O(1). */
    public @Nullable Layer<Owner> layerOrNull(String id) {
        if (id == null) {
            return null;
        }
        return layersById.get(id);
    }

    public void setSink(PoseSink sink) {
        this.sink = sink;
    }

    public void setClientSide(boolean clientSide) {
        this.clientSide = clientSide;
    }

    public int version() {
        return version;
    }

    public boolean isClientSide() {
        return clientSide;
    }

    public long tickCount() {
        return tickCount;
    }

    public void tick() {
        tick(1f / 20f);
    }

    public void tick(float dt) {
        Blender blender = new Blender();
        boolean changed = false;
        for (Layer<Owner> layer : layers) {
            if (layer.tick(this, dt, tickCount)) {
                changed = true;
            }
            blender.applyLayer(layer.mode(), layer.activePose(), layer.weight(), layer.boneMask());
        }
        if (sink != null && !blender.isEmpty()) {
            sink.apply(blender);
        }
        consumeTriggers();
        if (changed) {
            version++;
        }
        tickCount++;
    }

    //endregion

    //region triggers / signals / locks

    public void trigger(String name) {
        if (name != null) {
            triggers.put(name, Boolean.TRUE);
        }
    }

    boolean isTriggered(String name) {
        return triggers.getOrDefault(name, false);
    }

    void consumeTriggers() {
        triggers.clear();
    }

    public Blackboard data() {
        return data;
    }

    public Object signal(String name) {
        return data.get(name);
    }

    public void setSignal(String name, Object value) {
        data.put(name, value);
    }

    void lockLayer(String id, int ticks) {
        if (id != null && ticks > 0) {
            locks.merge(id, ticks, Integer::max);
        }
    }

    /** If this layer is locked, consume one tick of the lock and return true (skip its tick). */
    boolean consumeLock(String id) {
        Integer remaining = locks.get(id);
        if (remaining == null || remaining <= 0) {
            return false;
        }
        if (remaining <= 1) {
            locks.remove(id);
        } else {
            locks.put(id, remaining - 1);
        }
        return true;
    }

    /** Non-consuming check: is the named layer currently locked? */
    public boolean isLayerLocked(String id) {
        Integer remaining = locks.get(id);
        return remaining != null && remaining > 0;
    }

    //endregion

    //region reconcile surface (reserved data for future client/server sync)

    /** Imperative switch by id — scripting/JSON-friendly; inert if the layer/state is unknown. */
    public void goTo(String layerId, String stateId) {
        Layer<Owner> layer = layerOrNull(layerId);
        if (layer == null) {
            return;
        }
        State<Owner> target = layer.findState(stateId);
        if (target != null) {
            layer.goTo(target);
        }
    }

    /**
     * Snapshot of each layer's active state ({layer id &rarr; state id}). <b>Reserved</b> for a future
     * server&rarr;client reconcile layer &mdash; currently unused (the wire transport was deferred), but kept on
     * purpose so the data surface already exists when networking is reconsidered.
     */
    public Map<String, String> activeStates() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Layer<Owner> layer : layers) {
            snapshot.put(layer.id(), layer.active() == null ? null : layer.active().id());
        }
        return snapshot;
    }

    /**
     * Force each layer's active state by id (silent, no callbacks). <b>Reserved</b> for a future client
     * reconcile layer &mdash; currently unused. Bumps {@link #version}.
     */
    public void conform(Map<String, String> snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Layer<Owner> layer : layers) {
            String stateId = snapshot.get(layer.id());
            if (stateId != null) {
                layer.conformTo(stateId);
            }
        }
        version++;
    }

    //endregion

    //region StateReader — collaborative state read surface

    @Override
    public boolean has(StateQuery query) {
        return read(query) != null;
    }

    @Override
    public Object read(StateQuery query) {
        if (query.isEmpty()) return null;
        String source = query.source();
        return switch (source) {
            case "owner" -> owner;
            case "machine" -> readMachine(query.segment(1));
            case "layer" -> readLayer(query.segment(1), query.segment(2));
            case "data" -> data().get(query.subPath(1));
            case "signal" -> signal(query.subPath(1));
            case "trigger" -> isTriggered(query.subPath(1));
            default -> null;
        };
    }

    private Object readMachine(String prop) {
        if (prop == null) return null;
        return switch (prop) {
            case "tick", "tickCount" -> tickCount;
            case "version" -> version;
            case "client", "clientSide" -> isClientSide();
            default -> null;
        };
    }

    private Object readLayer(String layerId, String prop) {
        if (layerId == null || prop == null) return null;
        Layer<Owner> layer = layerOrNull(layerId);
        if (layer == null) return null;
        State<Owner> active = layer.active();
        return switch (prop) {
            case "id" -> layer.id();
            case "state" -> active == null ? null : active.id();
            case "mode" -> layer.mode();
            case "weight" -> layer.weight();
            case "locked" -> isLayerLocked(layer.id());
            case "elapsed" -> layer.stateElapsedTicks();
            case "duration" -> active == null ? -1 : active.durationTicks();
            case "transition" -> active == null ? null : layer.activeTransition();
            case "transitionElapsed" -> layer.transitionElapsed();
            default -> null;
        };
    }

    //endregion

    public static final class Builder<O> {

        private final StateMachine<O> machine;

        Builder(O owner) {
            this.machine = new StateMachine<>(owner);
        }

        public Builder<O> layer(String id, Consumer<Layer<O>> config) {
            Layer<O> layer = new Layer<>(id);
            config.accept(layer);
            layer.start();
            machine.layers.add(layer);
            machine.layersById.put(layer.id(), layer);
            return this;
        }

        public Builder<O> sink(PoseSink sink) {
            machine.sink = sink;
            return this;
        }

        public Builder<O> clientSide(boolean clientSide) {
            machine.clientSide = clientSide;
            return this;
        }

        public StateMachine<O> build() {
            return machine;
        }
    }
}
