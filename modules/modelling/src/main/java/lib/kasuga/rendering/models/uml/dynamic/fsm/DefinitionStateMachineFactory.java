package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmAction;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmCondition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Builds a runtime, owner-generic {@link StateMachine} from a data-driven
 * {@link StateMachineDefinition}. Behavior references are resolved through the provided
 * {@link FsmFunctionLibrary}; inline pose data is applied directly.
 */
public final class DefinitionStateMachineFactory {

    private final FsmFunctionLibrary library;

    public DefinitionStateMachineFactory(FsmFunctionLibrary library) {
        this.library = library;
    }

    /**
     * Build a {@link StateMachine} for the given owner using the supplied definition.
     * The returned machine is owner-typed as {@code Object}; callers that know the owner
     * type can wrap through a typed factory.
     */
    public StateMachine<Object> build(Object owner, StateMachineDefinition definition) {
        return build(owner, definition, null);
    }

    /**
     * Build and configure the full machine. This is the canonical entry point.
     */
    public StateMachine<Object> build(Object owner, StateMachineDefinition definition, PoseSink sink) {
        StateMachine<Object> machine = StateMachine.<Object>builder(owner)
                .clientSide(sink != null)
                .build();

        if (sink != null) {
            machine.setSink(sink);
        }

        for (LayerDefinition layerDef : definition.layers()) {
            Layer<Object> layer = new Layer<>(layerDef.id());
            layer.weight(layerDef.weight());
            switch (layerDef.mode()) {
                case ADDITIVE -> layer.additive();
                case OVERRIDE -> layer.override();
                default -> layer.base();
            }
            layer.boneMask(layerDef.resolvedMask());

            Map<String, State<Object>> stateById = new java.util.HashMap<>();
            for (StateDefinition stateDef : layerDef.states()) {
                State<Object> state = layer.state(stateDef.id(), s -> configureState(s, stateDef));
                stateById.put(stateDef.id(), state);
            }

            layer.initial(stateById.get(layerDef.initialState()));

            for (TransitionDefinition transDef : layerDef.transitions()) {
                State<Object> from = stateById.get(transDef.from());
                State<Object> to = stateById.get(transDef.to());
                if (from == null || to == null) {
                    continue;
                }
                Transition<Object> transition = layer.transition(transDef.id(), from, to);
                configureTransition(transition, transDef);
            }

            machine.layers().add(layer);
            layer.start();
        }

        return machine;
    }

    private void configureState(State<Object> state, StateDefinition def) {
        def.durationTicks().ifPresent(state::durationTicks);
        applyPose(state, def.pose());
        for (ResourceLocation id : def.onEnter()) {
            state.onEnter(action(id));
        }
        for (ResourceLocation id : def.onExit()) {
            state.onExit(action(id));
        }
        for (ResourceLocation id : def.onUpdate()) {
            state.onUpdate(action(id));
        }
    }

    private void configureTransition(Transition<Object> transition, TransitionDefinition def) {
        transition.crossFade(def.crossFadeSeconds());
        if (def.whenComplete()) {
            transition.whenComplete();
        }
        def.triggerOn().ifPresent(transition::on);
        for (ResourceLocation id : def.when()) {
            transition.when(condition(id));
        }
        for (ResourceLocation id : def.onFire()) {
            transition.onFire(action(id));
        }
    }

    private void applyPose(State<Object> state, PoseDefinition def) {
        for (Map.Entry<String, Float> morph : def.morphs().entrySet()) {
            state.morph(morph.getKey(), morph.getValue());
        }

        for (PoseDefinition.BoneDefinition boneDef : def.bones()) {
            state.bone(boneDef.name(), buildTransform(boneDef.transform()), ApplyMode.byName(boneDef.mode()));
        }

        for (PoseDefinition.FrameDefinition frameDef : def.frames()) {
            state.frame(frameDef.material(), frameDef.frame());
        }
    }

    private Transform buildTransform(TransformDefinition def) {
        Transform transform = new Transform();
        Vector3f t = def.translate();
        transform.translate(t.x, t.y, t.z);
        Vector3f r = def.rotate();
        transform.rotate(r.x, r.y, r.z, true);
        Vector3f s = def.scale();
        transform.scale(s.x, s.y, s.z);
        return transform;
    }

    private Predicate<StateContext<Object>> condition(ResourceLocation id) {
        FsmCondition condition = library.condition(id);
        return condition != null ? ctx -> condition.test(ctx) : ctx -> false;
    }

    private Consumer<StateContext<Object>> action(ResourceLocation id) {
        FsmAction action = library.action(id);
        return action != null ? action::accept : ctx -> {};
    }
}
