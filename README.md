# KasugaLib Next

KasugaLib Next is a Java 21, NeoForge 1.21.1 multi-module library project. The repository contains
the core mod, modelling and rendering support, scripting integration, Create integration, build
plugins and the aggregate distribution.

## Development

Import the Gradle project in IntelliJ IDEA, then use the existing module run configurations or the
wrapper directly:

```bash
./gradlew build
./gradlew :modules:modelling:runClient
```

The modelling development client includes the existing model tests and the content-testing particle
and black-hole demonstrations.

## Custom rendering

The custom rendering surface includes owner-scoped world pipelines, managed effects,
post-processing graphs, reload-safe resource/generated shaders, the imperative Java Shader DSL,
background shader preparation and schema-driven runtime parameters.

See [EFFECT_RENDERING.md](EFFECT_RENDERING.md) for the API behavior, examples and the GLSL-to-Java
Shader DSL guide.

Run the focused release gate with:

```bash
./gradlew renderingReleaseCheck
```

The manual and artifact checks for a release candidate are recorded in
[RENDERING_RELEASE_CHECKLIST.md](RENDERING_RELEASE_CHECKLIST.md).

## Main modules

- `modules/shader`: typed shader IR, Java DSL, GLSL 150 backend and build-time resource compiler.
- `modules/modelling`: model rendering, custom pipelines, effects, post-processing and runtime shader
  management.
- `modules/gradle-plugin`: Kasuga build plugins, including `builder.kasuga.shader`.
- `modules/library`: aggregate mod artifact.

Minecraft/Parchment mapping sources remain subject to their upstream terms. See the repository
license and release metadata for the project distribution terms.
