package lib.kasuga.rendering.models.uml.dynamic.state_machine;

import java.util.function.BiConsumer;

public class State {

    public final StateMachine machine;

    public final Transfer[] transfers;

    public final BiConsumer<Current, State> onEnter, action, onExit;

    public State(StateMachine machine,
                 Transfer[] transfers,
                 BiConsumer<Current, State> onEnter,
                 BiConsumer<Current, State> action,
                 BiConsumer<Current, State> onExit) {
        this.machine = machine;
        this.transfers = transfers;
        this.onEnter = onEnter;
        this.action = action;
        this.onExit = onExit;
    }
}
