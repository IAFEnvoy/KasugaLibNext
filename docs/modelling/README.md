# Modelling Module

中文版本：[README.zh-CN.md](README.zh-CN.md)

`modules/modelling` provides KasugaLib's client-side model loading, model-instance lifecycle, Minecraft renderer backend, texture atlas integration, and related animation/physics features.

This document describes the normal integration path for models that ship with a mod, plus the checks used while developing the module itself. The implementation is client-only: do not access its rendering APIs from a dedicated server.

## Prerequisites

- Java 21. The module's Gradle toolchain is pinned to Java 21.
- A client resource reload must complete before a scanned model can be instantiated. In a development client, restart the game or press `F3 + T` after changing model assets or `model_proxy.json`.
- The module is initialized by `lib.kasuga.rendering.models.mc.Constants`; it registers the built-in pipelines and the resource reload listener.

## Loading A Model From Resources

### 1. Add the asset

Place a model below your mod's resources. For example, this bbmodel resides at:

```text
src/main/resources/assets/examplemod/models/vehicles/engine.bbmodel
```

Its model key is therefore:

```text
examplemod:models/vehicles/engine.bbmodel
```

The path includes the `models/` prefix and the file extension. Do not use the vanilla model identifier convention (`examplemod:vehicles/engine`) for this API.

### 2. Opt in through `model_proxy.json`

The scanner only loads resources matched by:

```text
src/main/resources/assets/<namespace>/models/model_proxy.json
```

The JSON object maps a namespace to an array of resource-path patterns. A minimal configuration for the file above is:

```json
{
  "examplemod": [
    "models/vehicles/**/*.bbmodel"
  ]
}
```

Patterns are relative to the resource namespace:

| Pattern | Meaning |
| --- | --- |
| `*` | Any characters except `/` |
| `**` | Any characters across directories |
| `?` | One character |
| `!pattern` | Exclude resources matching `pattern` |

For example, `"models/**/*.obj"` includes OBJ files anywhere below `models`, while `"!models/legacy/**"` excludes the legacy subtree. The loader combines the namespaces declared by the proxy configurations it reads into one scanner configuration.

### 3. Use a supported extension

The built-in router chooses a pipeline by file extension:

| Extension | Pipeline | Source form |
| --- | --- | --- |
| `.geo.json` | Bedrock | JSON |
| `.json` | Java Edition | JSON |
| `.obj` | Wavefront OBJ | Text |
| `.mmd.zip` | PMX/MMD archive | ZIP |
| `.glb`, `.gltf` | glTF | Binary |
| `.bbmodel` | Blockbench native model | Text |

`.geo.json` is routed before generic `.json`. For an unsupported format, register a pipeline and a route through `PipelineRegistry` during client initialization.

## Creating And Rendering An Instance

After the resource reload has published the model, resolve its pipeline, create an instance, then add that instance to the Minecraft bridge/backend. The built-in names are `mc_bridge` and `mc_backend`.

```java
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;

ResourceLocation modelKey = ResourceLocation.fromNamespaceAndPath(
        "examplemod", "models/vehicles/engine.bbmodel"
);
ResourceLocation instanceKey = ResourceLocation.fromNamespaceAndPath(
        "examplemod", "engine_at_spawn"
);

ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline =
        PipelineRegistry.resolve(modelKey);
if (pipeline == null || !pipeline.hasModel(modelKey)) {
    // The proxy rule did not match, or resource reload has not completed yet.
    return;
}

if (!pipeline.hasInstance(modelKey, instanceKey)) {
    Transform transform = new Transform().translate(x, y, z);
    ModelInstance instance = pipeline.createInstance(modelKey, instanceKey, transform, null, null);
    if (instance != null) {
        pipeline.addToRenderer(modelKey, instanceKey, "mc_bridge", "mc_backend");
    }
}
```

Call this only on the client-side game/render flow. `createInstance` returns `null` when the model key is not published. Reuse a stable `instanceKey`; creating another instance with the same key replaces the pipeline's lookup entry without automatically removing the old renderable.

When an instance is no longer needed, remove it through the pipeline rather than retaining and closing it yourself:

```java
pipeline.removeInstance(modelKey, instanceKey);
```

This removes it from every registered backend and closes its runtime animation/physics state.

## Blockbench (`.bbmodel`)

The `bbmodel` pipeline supports native Blockbench JSON with cube and mesh elements, nested outliner groups, element/group visibility, rotations, per-face texture selection, face UV rotation, external textures, and embedded `data:image/...` textures.

Use `face.texture` as the texture-array index written by Blockbench. For external texture sources, resource locations may be written with or without `textures/` and `.png`; the loader normalizes them to atlas identifiers. For example, both `examplemod:textures/vehicles/engine.png` and `examplemod:vehicles/engine` resolve to the sprite `examplemod:vehicles/engine`.

Blockbench records UV coordinates in texture pixels. `KsgBbModelLoader` converts them to normalized UVs using the selected texture's actual dimensions while it creates vertices. This conversion is intentionally local to the bbmodel loader. Do not add pixel-UV normalization in `FlatModelData` or another shared backend path, because those paths are also used by OBJ, PMX, glTF, Bedrock, and Java Edition models.

The repository has runnable examples in:

```text
modules/modelling/src/main/resources/assets/kasuga_lib/models/block/test/blockbench/
```

## Development And Debugging

Run commands from the repository root. On Windows use `gradlew.bat`; on macOS/Linux use `./gradlew`.

### Compile And Unit Tests

```powershell
.\gradlew.bat :modules:modelling:compileJava
.\gradlew.bat :modules:modelling:test
.\gradlew.bat :modules:modelling:modelUnitTest
```

To run only the Blockbench loader tests:

```powershell
.\gradlew.bat :modules:modelling:test --tests lib.kasuga.rendering.models.mc.typo.bbmodel.BbModelDefinitionTest
```

`modelUnitTest` is the module's pure-Java test task. The regular `test` task uses the configured NeoForge test runtime.

### Start The Client With A Selected Smoke Model

The development client displays model smoke tests by default. The default selection is `bbmodel`; pass a filename or a complete resource path to show exactly one Blockbench model in front of the player:

```powershell
.\gradlew.bat :modules:modelling:runClient `
  '-PkasugaTestModel=bbmodel' `
  '-PkasugaTestBbmodel=qj_bogey_main.bbmodel'
```

The short `kasugaTestBbmodel` value is resolved under:

```text
models/block/test/blockbench/
```

Alternatively pass `models/vehicles/engine.bbmodel`. The selected resource must be included by the active `model_proxy.json` before the client starts; embedded textures are registered while the resource reload builds the atlas, so loading a new bbmodel after the atlas has been published is not supported.

Other built-in smoke selections are `obj`, `be`, and `je`; any other value takes the MMD test path:

```powershell
.\gradlew.bat :modules:modelling:runClient '-PkasugaTestModel=obj'
```

Set `-Dkasuga.renderTestModels=false` on the JVM to suppress all hardcoded smoke models when investigating unrelated rendering behavior.

### Logs And Failure Triage

The client run directory is:

```text
modules/modelling/run/client/
```

Inspect `logs/latest.log` after a resource reload or client run.

| Symptom | Checks |
| --- | --- |
| Model is absent | Confirm the exact `ResourceLocation`, make sure `model_proxy.json` matches it, then restart/reload resources. Look for the warning `Test bbmodel ... is unavailable after resource reload`. |
| Wrong model appears | Ensure the `kasugaTestModel` and `kasugaTestBbmodel` Gradle properties are both set. A short bbmodel name always resolves under the test Blockbench directory. |
| Green/missing texture | Verify external texture namespace/path, check that the texture exists below `assets/<namespace>/textures`, and search the log for atlas/sprite errors. |
| Texture appears tiled or granular | Check the `.bbmodel` texture width/height and its face UVs. The loader expects Blockbench pixel UVs and normalizes them once. Do not compensate by changing shared backend UV logic. |
| Model exists but is not visible | Check `pipeline.hasModel`, `pipeline.hasInstance`, then `pipeline.isRendering(modelKey, instanceKey, "mc_backend")`. Confirm the transform is in front of the camera and use `removeInstance` before recreating a changed instance. |
| Loader error | Search `latest.log` for `Invalid Blockbench model`, `Unable to decode embedded texture`, and the model's resource path. |

## Relevant Source Locations

| Area | Source |
| --- | --- |
| Client initialization and smoke renderer | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/Constants.java` |
| Built-in pipelines and extension routes | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/registry/PipelineRegistry.java` |
| Model lifecycle API | `modules/modelling/src/main/java/lib/kasuga/rendering/models/uml/dynamic/ModelPipeLine.java` |
| Blockbench loader | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/typo/bbmodel/KsgBbModelLoader.java` |
| Resource-proxy matcher | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/source/model/ModelProxyConfig.java` |
