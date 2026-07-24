# Agent Notes

## Modelling module unit tests

Run the pure-Java modelling tests (no NeoForge/FML runtime) with:

```bash
JAVA_HOME=/Users/vfyjxf/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home \
  ./gradlew :modules:modelling:modelUnitTest --no-daemon
```

The default system JVM on this machine is Java 25 (JBR-25). Running Gradle with
Java 25 causes `:gradle-plugin:compileGroovy` to fail because Groovy cannot read
class file major version 69. Use a Java 21 JDK for the Gradle daemon instead.

## Multiplexer design and API

The multiplexer in `lib.kasuga.rendering.models.uml.dynamic.multiplexer` is a generic,
stateless *context-state selector*. It is intentionally decoupled from Minecraft; the
Minecraft-specific implementation lives in `lib.kasuga.rendering.models.mc.multiplexer`.

### Core types

- `Context` – read-only input snapshot. Exposes `String property(String)` and `Blackboard data()`.
  Minecraft concrete: `McContext`.
- `Variant<V>` – self-typed node in the variant graph. Concrete subclasses carry the payload
  (e.g. model references). `VariantFactory<V>` creates them by id.
- `MuxState<V>` – per-instance *runtime* state held by the owner. Contains current variant and
  cross-fade progress. Do not store inside the `Multiplexer` definition.
- `Multiplexer<C extends Context, V extends Variant<V>>` – stateless definition: a list of
  variants, guarded transitions, and an initial variant. One definition can be shared across many
  runtime `MuxState` instances.

### Key API

```java
Multiplexer<MyContext, MyVariant> def = Multiplexer.define(MyVariant::new, mux -> {
    MyVariant off = mux.variant("off", v -> { });
    MyVariant on  = mux.variant("on",  v -> { });
    mux.transition(off, on, t -> t
        .when(SelectorPredicates.propertyIs("powered", "true"))
        .crossFade(0.2f)
        .onSwitch(state -> { }));
    mux.initial(off);
});

MuxState<MyVariant> state = def.newState();
def.advance(state, context, dt);   // per tick / event
MyVariant selected = def.select(context, state); // read-only query
```

### Performance note

`Multiplexer` keeps a `Map<Variant, List<Transition>>` (`transitionsByFrom`) so each
`advance`/`select` only evaluates transitions that originate from the *current* variant,
rather than scanning the full transition list. The same optimization exists in the FSM `Layer`.

### Predicate helpers

`SelectorPredicates` provides common guards: `always()`, `propertyIs(name, value)`,
`dataFlag(key)`, `dataEquals(key, value)`, `rawEquals(name, expected)`.
