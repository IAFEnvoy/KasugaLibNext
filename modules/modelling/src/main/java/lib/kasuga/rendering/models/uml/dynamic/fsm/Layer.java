package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One parallel state graph + its blend properties ({@link BlendMode} / {@code weight} / {@link BoneMask}).
 * Layer UNIFIES the old "region" and "blend layer" concepts: multiple layers = parallel/orthogonal.
 *
 * <p>Built inside a {@code layer(id, layer -> ...)} lambda. {@link #state(String)} returns a typed
 * {@link State} handle, used in {@code transition(id, from, to)} (compile-time safe, no string ids).
 */
public final class Layer<Owner> {

    final String id;
    final List<State<Owner>> states = new ArrayList<>();
    final List<Transition<Owner>> transitions = new ArrayList<>();
    private Map<State<Owner>, List<Transition<Owner>>> transitionsByFrom;
    State<Owner> initial;

    // runtime
    State<Owner> active;
    Transition<Owner> activeTransition;
    float transitionElapsed;
    int stateElapsedTicks;
    State<Owner> pendingGoTo;
    boolean activeChanged;

    // blend props
    BlendMode mode = BlendMode.BASE;
    float weight = 1f;
    BoneMask boneMask = BoneMask.all();

    public Layer(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("layer id required");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    //region construction

    public State<Owner> state(String id) {
        return state(id, s -> {});
    }

    public State<Owner> state(String id, Consumer<State<Owner>> config) {
        State<Owner> state = new State<>(id);
        config.accept(state);
        states.add(state);
        return state;
    }

    public Layer<Owner> initial(State<Owner> state) {
        this.initial = state;
        return this;
    }

    public Transition<Owner> transition(String id, State<Owner> from, State<Owner> to) {
        Transition<Owner> transition = new Transition<>(id, this, from, to);
        transitions.add(transition);
        return transition;
    }

    public Layer<Owner> base() {
        this.mode = BlendMode.BASE;
        return this;
    }

    public Layer<Owner> additive() {
        this.mode = BlendMode.ADDITIVE;
        return this;
    }

    public Layer<Owner> override() {
        this.mode = BlendMode.OVERRIDE;
        return this;
    }

    public Layer<Owner> weight(float weight) {
        this.weight = weight;
        return this;
    }

    public Layer<Owner> boneMask(BoneMask mask) {
        this.boneMask = mask;
        return this;
    }

    //endregion

    //region accessors

    public BlendMode mode() {
        return mode;
    }

    public float weight() {
        return weight;
    }

    public BoneMask boneMask() {
        return boneMask;
    }

    public State<Owner> active() {
        return active;
    }

    /** External imperative switch within this layer. */
    public Layer<Owner> goTo(State<Owner> target) {
        this.pendingGoTo = target;
        return this;
    }

    void requestGoTo(State<Owner> target) {
        this.pendingGoTo = target;
    }

    State<Owner> findState(String id) {
        if (id == null) {
            return null;
        }
        for (State<Owner> state : states) {
            if (state.id().equals(id)) {
                return state;
            }
        }
        return null;
    }

    /** Client reconciliation: set the active state by id, silently (no onExit/onEnter callbacks). */
    void conformTo(String stateId) {
        State<Owner> target = findState(stateId);
        if (target == null) {
            return;
        }
        active = target;
        activeTransition = null;
        stateElapsedTicks = 0;
    }

    //endregion

    //region runtime

    void start() {
        active = initial != null ? initial : (states.isEmpty() ? null : states.get(0));
        stateElapsedTicks = 0;

        Map<State<Owner>, List<Transition<Owner>>> byFrom = new IdentityHashMap<>();
        for (Transition<Owner> transition : transitions) {
            byFrom.computeIfAbsent(transition.from, k -> new ArrayList<>()).add(transition);
        }
        this.transitionsByFrom = Collections.unmodifiableMap(byFrom);
    }

    boolean tick(StateMachine<Owner> machine, float dt, long tickCount) {
        activeChanged = false;
        if (machine.consumeLock(id)) {
            // Frozen: keep the active pose, skip transitions.
            return false;
        }
        StateContext<Owner> ctx = new StateContext<>(machine, this, active, tickCount);

        if (active != null) {
            runActions(active.onUpdate, ctx);
        }

        if (pendingGoTo != null && states.contains(pendingGoTo)) {
            forceEnter(pendingGoTo, ctx);
            pendingGoTo = null;
            stateElapsedTicks++;
            return activeChanged;
        }

        if (activeTransition != null) {
            transitionElapsed += dt;
            if (transitionElapsed >= activeTransition.crossFadeSeconds) {
                completeTransition(ctx);
            } else {
                return activeChanged;
            }
        }

        boolean sourceComplete = active != null
                && active.hasDuration()
                && stateElapsedTicks >= active.durationTicks;

        List<Transition<Owner>> candidates = transitionsByFrom.get(active);
        if (candidates != null) {
            for (Transition<Owner> transition : candidates) {
                if (transition.fires(ctx, sourceComplete)) {
                    fire(transition, ctx);
                    break;
                }
            }
        }
        stateElapsedTicks++;
        return activeChanged;
    }

    private void fire(Transition<Owner> transition, StateContext<Owner> ctx) {
        runActions(transition.onFire, ctx);
        if (transition.isInstant()) {
            if (active != null) {
                runActions(active.onExit, ctx);
            }
            active = transition.to;
            stateElapsedTicks = 0;
            if (active != null) {
                runActions(active.onEnter, ctx);
            }
            activeChanged = true;
        } else {
            activeTransition = transition;
            transitionElapsed = 0f;
        }
    }

    private void forceEnter(State<Owner> target, StateContext<Owner> ctx) {
        if (active != null) {
            runActions(active.onExit, ctx);
        }
        active = target;
        activeTransition = null;
        stateElapsedTicks = 0;
        if (active != null) {
            runActions(active.onEnter, ctx);
        }
        activeChanged = true;
    }

    private void completeTransition(StateContext<Owner> ctx) {
        Transition<Owner> transition = activeTransition;
        if (active != null) {
            runActions(active.onExit, ctx);
        }
        active = transition.to;
        activeTransition = null;
        stateElapsedTicks = 0;
        if (active != null) {
            runActions(active.onEnter, ctx);
        }
        activeChanged = true;
    }

    private static <O> void runActions(List<Consumer<StateContext<O>>> actions, StateContext<O> ctx) {
        if (actions == null) {
            return;
        }
        for (Consumer<StateContext<O>> action : actions) {
            action.accept(ctx);
        }
    }

    /** The pose this layer contributes this tick (cross-faded if a transition is in progress). */
    Pose activePose() {
        if (active == null) {
            return Pose.empty();
        }
        if (activeTransition != null) {
            float alpha = activeTransition.crossFadeSeconds <= 0f
                    ? 1f
                    : Math.min(1f, transitionElapsed / activeTransition.crossFadeSeconds);
            return blend(active.buildPose(), activeTransition.to.buildPose(), alpha);
        }
        return active.buildPose();
    }

    private static Pose blend(Pose from, Pose to, float alpha) {
        if (alpha <= 0f) {
            return from;
        }
        if (alpha >= 1f) {
            return to;
        }

        Pose.Builder builder = new Pose.Builder();

        Set<Object> morphKeys = new HashSet<>();
        morphKeys.addAll(from.morphs().keySet());
        morphKeys.addAll(to.morphs().keySet());
        for (Object key : morphKeys) {
            Pose.Morph mf = from.morphs().get(key);
            Pose.Morph mt = to.morphs().get(key);
            float valueFrom = mf != null ? mf.value() : 0f;
            float valueTo = mt != null ? mt.value() : 0f;
            float factorFrom = mf != null ? mf.factor() : 1f;
            float factorTo = mt != null ? mt.factor() : 1f;
            builder.morph(key, lerp(valueFrom, valueTo, alpha), lerp(factorFrom, factorTo, alpha));
        }

        Set<String> boneKeys = new HashSet<>();
        boneKeys.addAll(from.bones().keySet());
        boneKeys.addAll(to.bones().keySet());
        Transform scratch = new Transform();
        for (String key : boneKeys) {
            Pose.Bone bf = from.bones().get(key);
            Pose.Bone bt = to.bones().get(key);
            ApplyMode applyMode = bt != null ? bt.mode() : (bf != null ? bf.mode() : ApplyMode.REPLACE);
            if (bf != null && bt != null) {
                TransformLerp.lerp(bf.transform(), bt.transform(), alpha, scratch);
                builder.bone(key, scratch, applyMode);
            } else if (bf != null) {
                builder.bone(key, bf.transform(), applyMode);
            } else {
                builder.bone(key, bt.transform(), applyMode);
            }
        }

        Set<Object> frameKeys = new HashSet<>();
        frameKeys.addAll(from.frames().keySet());
        frameKeys.addAll(to.frames().keySet());
        for (Object key : frameKeys) {
            Pose.Frame ff = from.frames().get(key);
            Pose.Frame tf = to.frames().get(key);
            int frame;
            if (alpha < 0.5f) {
                frame = ff != null ? ff.frame() : (tf != null ? tf.frame() : 0);
            } else {
                frame = tf != null ? tf.frame() : (ff != null ? ff.frame() : 0);
            }
            builder.frame(key, frame);
        }

        return builder.build();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    //endregion
}
