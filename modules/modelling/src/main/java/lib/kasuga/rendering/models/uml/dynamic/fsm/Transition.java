package lib.kasuga.rendering.models.uml.dynamic.fsm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A transition between two {@link State}s (referenced by handle, not string id). {@code from}/{@code to}
 * are a required pair, set at construction:
 * {@code layer.transition("id", from, to).when(ctx -> ...).on("attack").whenComplete()}.
 *
 * <p>Fires when: (whenComplete-satisfied) AND (trigger active, if {@code .on(...)}) AND (all {@code .when} hold).
 */
public final class Transition<Owner> {

    final String id;
    final Layer<Owner> layer;
    final State<Owner> from;
    final State<Owner> to;
    final List<Predicate<StateContext<Owner>>> whens = new ArrayList<>();
    String triggerOn;
    boolean whenComplete;
    float crossFadeSeconds;
    final List<Consumer<StateContext<Owner>>> onFire = new ArrayList<>();

    Transition(String id, Layer<Owner> layer, State<Owner> from, State<Owner> to) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("transition id required");
        }
        if (from == null) {
            throw new IllegalArgumentException("transition 'from' state required");
        }
        if (to == null) {
            throw new IllegalArgumentException("transition 'to' state required");
        }
        this.id = id;
        this.layer = layer;
        this.from = from;
        this.to = to;
    }

    //region configuration

    /** Guard predicate (multiple {@code .when} = AND). */
    public Transition<Owner> when(Predicate<StateContext<Owner>> guard) {
        whens.add(guard);
        return this;
    }

    /** Fire on a named trigger. */
    public Transition<Owner> on(String trigger) {
        this.triggerOn = trigger;
        return this;
    }

    /** Fire when the source state's {@code durationTicks} elapses. */
    public Transition<Owner> whenComplete() {
        this.whenComplete = true;
        return this;
    }

    public Transition<Owner> crossFade(float seconds) {
        this.crossFadeSeconds = seconds;
        return this;
    }

    public Transition<Owner> onFire(Consumer<StateContext<Owner>> callback) {
        onFire.add(callback);
        return this;
    }

    //endregion

    //region accessors

    public String id() {
        return id;
    }

    public Layer<Owner> layer() {
        return layer;
    }

    public State<Owner> from() {
        return from;
    }

    public State<Owner> to() {
        return to;
    }

    public String triggerOn() {
        return triggerOn;
    }

    public boolean isWhenComplete() {
        return whenComplete;
    }

    public float crossFadeSeconds() {
        return crossFadeSeconds;
    }

    //endregion

    boolean fires(StateContext<Owner> ctx, boolean sourceComplete) {
        if (whenComplete && !sourceComplete) {
            return false;
        }
        if (triggerOn != null && !ctx.machine().isTriggered(triggerOn)) {
            return false;
        }
        for (Predicate<StateContext<Owner>> guard : whens) {
            if (!guard.test(ctx)) {
                return false;
            }
        }
        return true;
    }

    boolean isInstant() {
        return crossFadeSeconds <= 0f;
    }
}
