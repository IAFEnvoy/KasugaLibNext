package lib.kasuga.rendering.models.uml.dynamic.state_machine;

import java.util.ArrayList;
import java.util.List;

public class StateMachine {

    public final List<State> states;

    public final List<Transfer> transfers;

    public StateMachine() {
        this.states = new ArrayList<>();
        this.transfers = new ArrayList<>();
    }
}
