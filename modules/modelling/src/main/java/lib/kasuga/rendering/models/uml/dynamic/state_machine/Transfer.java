package lib.kasuga.rendering.models.uml.dynamic.state_machine;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public class Transfer {

    public final StateMachine machine;

    public final State from, to;

    public final BiPredicate<Current, Transfer> condition, shouldEnterNextState;

    public final BiConsumer<Current, Transfer> action;

    public Transfer(StateMachine machine, State from, State to,
                    BiPredicate<Current, Transfer> condition,
                    BiPredicate<Current, Transfer> shouldEnterNextState,
                    BiConsumer<Current, Transfer> action) {
        this.machine = machine;
        this.from = from;
        this.to = to;
        this.condition = condition;
        this.shouldEnterNextState = shouldEnterNextState;
        this.action = action;
    }
}
