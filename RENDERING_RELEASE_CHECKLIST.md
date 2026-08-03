# Rendering release checklist

This checklist covers the custom rendering pipeline, post-processing graph, Java Shader DSL,
generated-shader build plugin and runtime-exposed parameters. It does not change existing model
test behavior or project/artifact naming.

## Automated gate

Run:

```bash
./gradlew renderingReleaseCheck
```

The gate verifies:

- Shader DSL tests and generated-source golden output.
- Gradle shader-plugin wiring and stale generated-resource cleanup.
- Shader and modelling Javadoc generation.
- Pure-Java modelling/effect tests.
- Development content compilation, including the particle and black-hole demos.
- Modelling binary/sources/Javadoc artifacts and final library assembly with the shader module included.

## API gate

- Public descriptors and registrations have ownership and replacement semantics documented.
- Resource reload does not invalidate stable handles or user parameter values.
- Internal render-thread entry points remain marked internal.
- Exposed shader parameters validate name, description, type, range and default values.
- Resource shaders fail clearly when an exposed uniform is absent or incompatible.
- No public API depends on the black-hole demo.

## Runtime gate

- Start a client with automatic and explicit shader preparation worker counts.
- Open the effect inspector at narrow and wide GUI scales.
- Exercise generated particles and the black-hole post-process demo.
- Change, reset and persist exposed parameters while the effect is visible.
- Reload resources repeatedly and resize the game window.
- Verify behavior with the supported Iris/Sodium development configuration.

## Artifact gate

- Inspect the normal and shaded library JAR contents.
- Verify generated `.vsh`, `.fsh` and `.json` resources are packaged once.
- Verify Maven publications contain binary, sources, Javadoc and POM artifacts.
- Confirm release metadata, license choice, authors, description and repository URLs before upload.
- Record supported Minecraft, NeoForge and Java versions in release notes.

## External release

- Create a release candidate from a clean reviewed commit.
- Run CI from that exact commit.
- Publish artifacts only after the local and CI gates pass.
- Tag and announce the version only after artifact checksums and contents are verified.
