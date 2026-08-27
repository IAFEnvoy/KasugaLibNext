package lib.kasuga.rendering.models.uml.dynamic.state_machine;

public class Current {

    protected State state;

    protected Transfer transfer;

    public final DataProvider environment, cachedValue;

    public final StateMachine machine;

    public Current(StateMachine machine, DataProvider environment, DataProvider cachedValue) {
        this.environment = environment;
        this.machine = machine;
        this.cachedValue = cachedValue;
        state = machine.states.getFirst();
    }

    public void tickTransfer() {
        for (Transfer transfer : state.transfers) {
            if (transfer.condition.test(this, transfer)) {
                state.onExit.accept(this, state);
                this.transfer = transfer;
                break;
            }
        }
    }

    public void tickEnterState() {
        if (transfer != null && transfer.shouldEnterNextState.test(this, transfer)) {
            state = transfer.to;
            state.onEnter.accept(this, state);
            transfer = null;
        }
    }

    public void tick() {
        tickTransfer();
        if (transfer == null) {
            state.action.accept(this, state);
        } else {
            tickEnterState();
            if (transfer != null) {
                transfer.action.accept(this, transfer);
            } else {
                state.action.accept(this, state);
            }
        }
    }
}
