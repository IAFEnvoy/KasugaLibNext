# Kasuga 自定义渲染行为规范

Kasuga 通过 `RenderPipelineRegistrar` 统一注册 shader、world pipeline、managed effect 和
post-processing。

## 注册、所有权与生命周期

- `RenderPipelineRegistrar` 是 shader、world pipeline、managed effect、post-process pass 和
  post-process graph 的统一注册入口。
- `RegisterRenderPipelinesEvent` 在客户端 mod bus 上触发一次。通过该事件取得的 registrar
  随客户端存活。
- 动态内容使用 `RenderPipelineScope`。它与事件 registrar 暴露相同的注册行为。
- 每个 registrar 都绑定一个非空 owner。所有 registration 和诊断快照都保留该 owner。

静态内容从客户端注册事件取得 registrar：

```java
@SubscribeEvent
public static void registerRendering(RegisterRenderPipelinesEvent event) {
    RenderPipelineRegistrar pipelines = event.registrar(
            ResourceLocation.fromNamespaceAndPath("example", "rendering")
    );
    installRendering(pipelines);
}
```

动态模块持有一个 scope，并在卸载时关闭它：

```java
try (RenderPipelineScope scope = RenderPipelineScope.create(
        ResourceLocation.fromNamespaceAndPath("example", "script/session_42")
)) {
    installRendering(scope);
}
```

- `RenderPipelineScope.child(owner)` 创建独立 owner 的子 scope；父 scope 关闭时也会关闭子
  scope。
- Scope 按注册的相反顺序关闭资源。关闭操作可重复调用；关闭后的 scope 不再接受新资源。
- 所有 registration 都提供 `id()`、`owner()`、`isActive()` 和 `close()`。Registration 只代表
  注册时产生的那一个条目，不按 ID 跟随后续替换项。
- Shader registry 与 world-pipeline registry 分别约束 ID 唯一性。World callback、managed
  effect、post-process pass 和 graph 都占用 world-pipeline ID。
- `DuplicatePolicy.FAIL` 在 ID 已存在时直接拒绝注册，并报告当前 owner。
- `DuplicatePolicy.REPLACE` 用新条目替换当前条目，使旧 registration 失效。随后关闭旧
  registration 不会删除替换项。

## Pipeline 描述与执行

- `RenderPipelineDescriptor` 是不可变值，由 ID、placement、priority 和 `RenderDrawState`
  组成；缺少 placement 时构建失败。
- Placement 可以是 Kasuga 语义 `RenderPhase`，也可以是原生 NeoForge
  `RenderLevelStageEvent.Stage`。原生 placement 不产生语义 phase。
- `RenderPhase.POST_PROCESS` 映射到原生 `AFTER_LEVEL`。在这个原生 stage 内，执行顺序固定为
  `POST_PROCESS`、原生 `AFTER_LEVEL` placement、语义 `AFTER_LEVEL`。
- 同一执行位置内，priority 数值较小的管线先运行；priority 相同时按 ID 字符串排序。
- Registry 为每个原生 stage 缓存不可变执行快照。注册、替换或关闭会使相关快照失效。
- World pipeline callback 在客户端 render thread 上运行。仅在客户端世界存在时调用。
- 每次 callback 都获得独立 push 后的 pose stack；返回或抛出异常后都会 pop。
- 单个 callback 抛出的运行时异常会被记录，不会阻止同一 stage 的后续管线执行。
- Callback 自行开始的 buffer batch 必须在返回前结束。

一个 world pipeline 可以直接使用 descriptor 已编译的 `RenderType`：

```java
RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(
                ResourceLocation.fromNamespaceAndPath("example", "energy_field"),
                RenderPhase.AFTER_PARTICLES
        )
        .priority(100)
        .draw(draw -> draw
                .vertexFormat(DefaultVertexFormat.POSITION_COLOR)
                .primitiveMode(VertexFormat.Mode.QUADS)
                .blend(PipelineBlendMode.ADDITIVE)
                .depthTest(PipelineDepthTest.LEQUAL)
                .cull(PipelineCullMode.DISABLED)
                .writeMask(PipelineWriteMask.COLOR))
        .build();

PipelineRegistration registration = pipelines.world(
        descriptor,
        context -> renderEnergyField(context, context.pipeline().renderType())
);
```

## Draw state 与 RenderType

- `RenderDrawState` 是不可变的 Minecraft draw-state 描述，可以独立复用或通过
  `toBuilder()` 派生。
- 默认状态为 `POSITION`、`QUADS`、1536 字节 buffer、position shader、无纹理、无混合、
  `LEQUAL` depth test、启用 cull、主 framebuffer 以及 color/depth 写入。
- Buffer size 必须大于零。所有 state shard 都必须非空。
- 稳定枚举用于常规 blend、depth、cull、layering、target 和 write-mask 选择；高级调用方可以
  直接提供原生 `RenderStateShard`。
- 每次 pipeline 注册都会创建一个 `CompiledRenderPipeline`。默认 `RenderType` 在首次访问时
  构建并缓存。
- 基于 texture、blur 和 mipmap 的纹理变体按完整组合缓存。高级纹理状态变体按调用方提供的
  非空名称缓存。
- `CompiledRenderPipeline` 的缓存只属于当前 registration；替换 registration 会产生新的缓存。

## Shader 来源与 registration

- `RenderShaderDescriptor` 支持资源 shader、generated shader 和自定义 factory 三种来源。
- Fullscreen generated program 默认使用 `DefaultVertexFormat.BLIT_SCREEN`。Graphics generated
  program 必须显式提供 Minecraft vertex format。
- Descriptor 默认使用 `EAGER`、priority 0 和 `DISABLE_PIPELINE`。
- `ShaderRegistration` 和 `RenderShaderHandle` 都绑定到精确 registration。
- Handle 对象跨 F3+T 保持稳定，但其内部 `ShaderInstance` 会失效并安装新 generation。
- Handle 同时持有公开参数的默认 `ShaderParameterBlock`；参数值跨 F3+T 保持，不依赖当前
  `ShaderInstance`。
- `generation()` 只在新的编译实例成功安装后递增。
- `get()` 在 shader 不可用时返回 `null`；`shader()` 返回空值；`require()` 抛出异常。
- `status()` 返回当前 state、load origin、队列位置、等待时间、generation、prepare/compile
  耗时、translation cache 命中情况和最近错误。
- 可观察状态包括 `REGISTERED`、`PREPARING`、`QUEUED`、`COMPILING`、`READY`、`FAILED` 和
  `CLOSED`。
- 通过 registry ID 查询或预载只作用于该 ID 当前活动的 registration；通过 registration 或
  handle 操作不会作用于同 ID 的替换项。

## Shader preparation、编译与重载

- `EAGER` shader 在 client setup 注册后立即开始 generated-source preparation，并在 Minecraft
  初始 `RegisterShadersEvent` 中完成 compile/link；在资源系统已经可用后注册时转入逐帧编译队列。
- `DEFERRED` 不阻塞正常 resource reload，在资源可用后转入逐帧编译队列。
- `MANUAL` 不会自动排队，只有显式调用 registration、handle 或 registry preload 后才尝试
  排队。
- Generated shader 的 IR 到 GLSL/JSON 翻译属于 CPU preparation；OpenGL compile/link 只在
  render thread 上执行。
- Preparation 使用独立的有界 executor，排队容量为 128，不使用 JVM common pool。默认 worker
  数量为可用 CPU 核心数的一半、最少 1、最多 4；client setup 会预启动这些 worker。
- `ShaderPreparationScheduler.configureWorkers(0)` 使用自动数量；正数表示用户请求值，并自动限制
  到 JVM 可见的 CPU 核心数。启动参数 `-Dkasuga.shaderPreparationWorkers=N` 提供相同配置，`0`
  表示自动；客户端内可用 `/kasuga_effects workers N` 动态调整，调整立即作用于排队和后续任务。
- Preparation 结果进入最多 128 项的 LRU translation cache。相等的 `ShaderProgram` 会复用已
  生成 bundle 和编码后的 Minecraft resources；同一 program 的并发请求只执行一次翻译，不同
  program 可以由多个 worker 并行准备。
- Preparation 队列已满时 registration 进入失败状态，不会退回到调用线程同步执行。
- Registration 被关闭或替换时，尚未开始的 preparation 会被取消并从 executor 队列移除。
- 逐帧编译队列默认在每帧启动下一项前检查 2ms 预算，且每帧最多启动 4 项。单次 OpenGL
  compile 开始后不能被中断，因此实际帧耗时可以超过预算。
- 编译队列先按 preload priority、再按入队顺序选择。数值较小的 priority 更早执行。
- 同一 owner 每帧优先最多使用 2 个编译名额；有其他 owner 等待时会让出后续名额，没有其他
  owner 可运行时可以继续使用剩余额度。
- `whenReady()` 只等待当前精确 registration。到达 `READY` 时以 `ShaderStatus` 完成；进入
  `FAILED`、`CLOSED` 或被替换时异常完成。调用方取消 future 后，registry 会移除对应 waiter。
- Shader 已经 ready 时，`whenReady()` 返回已完成 future；其他异步完成发生在 render thread。
- `ShaderLoadListener` 的 ready、failure 和 invalidated 回调都在 render thread 上执行。Listener
  异常会被记录，不改变 shader 状态。
- `DISABLE_PIPELINE` 将加载失败限制在当前 shader，使其保持不可用并允许其他资源继续加载。
- `FAIL_RELOAD` 在 active resource reload 中发生编译失败时中止该次 reload；late preload 没有
  可中止的 reload，失败仍表现为 `FAILED`。
- Resource reload 创建的 shader 由 Minecraft 管理；late preload 创建的 shader 由 registry
  管理，并在关闭、替换或后续 generation 成功安装时于 render thread 释放。

## Java Shader DSL

- DSL 不解析 Java AST，也不反编译 lambda。Builder lambda 在 Java 中立即执行，并生成带类型的
  shader IR。
- 普通 Java 条件和循环只控制 IR 的构建过程，不会出现在最终 shader 中；需要保留到 GLSL 的
  动态条件与循环必须通过 shader builder 声明。
- 保存在普通 Java 变量中的表达式在每个使用点内联，不会自动生成 GLSL local。
- `let*` 创建有初始化值的 GLSL local；需要后续赋值时使用返回 `ShaderVariable` 的 `local*`。
- Fullscreen program 只包含 fragment module，并使用内建 fullscreen vertex stage。
- Graphics program 同时包含 vertex 和 fragment module。`position(...)` 写入 `gl_Position`。
- 每个 fragment input 必须存在同名同类型的 vertex output，否则 program 构建失败。
- 两个 stage 中同名 uniform 或 sampler 的声明必须完全一致；一致声明在 Minecraft shader JSON
  中只出现一次。
- Program ID 必须符合小写 `namespace:path` 资源 ID 格式。
- 当前 profile 支持 float、int、bool、vec2、vec3、vec4、mat2、mat3、mat4、sampler2D、float
  uniform array、命名 struct、stage input/output、局部变量、结构化控制流、常用数学运算和
  纹理采样。
- 当前 backend 输出 GLSL 150 和 Minecraft core-shader JSON。
- Runtime generated bundle 在加载自身 `.vsh`、`.fsh` 和 `.json` 时优先于资源包；bundle 中没有
  的资源和 import 会回退到当前客户端 `ResourceProvider`。
- Graphics program 的 DSL stage interface 会在 Java 构建期校验；传给
  `RenderShaderDescriptor` 的 Minecraft vertex format 是否与 attribute 匹配仍由调用方保证。

## 用户可调 Shader 参数

Shader 不会自动公开所有 uniform。开发者必须逐项注册允许用户修改的参数；每项参数固定包含
`name`、`description`、`ShaderParameterType`、inclusive `range` 和 `defaultValues`。名称同时是
GLSL uniform 名称，因此必须是合法且非保留的 GLSL identifier。

```java
public static final ShaderParameter EXPOSURE = ShaderParameter.floatParameter(
        "Exposure",
        "Controls the final output exposure",
        1.0f,  // default
        0.0f,  // minimum
        4.0f   // maximum
);

public static final ShaderParameter TINT = ShaderParameter.builder(
                "Tint",
                "Multiplies the output color",
                ShaderParameterType.COLOR_RGB
        )
        .range(0.0, 1.0)
        .defaultValues(1.0f, 1.0f, 1.0f)
        .build();
```

Generated shader 通过对应类型的 `expose*` 方法声明 uniform 并注册参数规格。普通 `uniform*`
保持内部状态，不会出现在公开 schema 中。

```java
ShaderProgram program = ShaderProgram.fullscreen("example:adjustable", shader -> {
    FloatExpr exposure = shader.exposeFloat(EXPOSURE);
    Vec3Expr tint = shader.exposeVec3(TINT);
    Vec3Expr color = shader.sampler2D("SceneSampler").sample(shader.texCoord()).rgb();
    shader.fragmentColor(shader.vec4(color.mul(tint).mul(exposure), shader.f32(1.0f)));
});
```

资源 shader 和 custom factory 无法调用 DSL，可在 descriptor 上注册同一份规格。资源中的 uniform
必须与参数 `name` 和底层 GLSL 类型匹配；首次绑定时缺少 uniform 会直接报错。

```java
RenderShaderDescriptor descriptor = RenderShaderDescriptor.builder(id, format)
        .resource()
        .expose(EXPOSURE)
        .expose(TINT)
        .build();
```

注册后的 `RenderShaderHandle.parameters()` 是该 registration 的默认运行时参数块。修改会验证组件
数量、数值类型、有限值和 range，不会重新生成或编译 shader。`reset(name)` 使用声明的 default，
`resetAll()` 恢复所有 default。

```java
RenderShaderHandle handle = registration.handle();
handle.parameters().setFloat(EXPOSURE, 1.35f);
handle.parameters().set(TINT, 1.0, 0.55, 0.2);
```

将 handle 传给 `PostProcessContext.fullscreen(...)` 或 `PostProcessGraphContext.fullscreen(...)` 时，
默认参数块在 draw 前自动上传。传入裸 `ShaderInstance` 时不会隐式猜测 registration；调用方应使用
`.parameters(block)` 显式绑定。需要同一 Shader 的多个独立配置时使用
`handle.createParameterBlock()`。

当前 `ShaderParameterType` 包含 float、integer、boolean、vec2/3/4、RGB/RGBA color 和
mat2/3/4。Range 对所有组件生效；boolean 固定使用 `[0, 1]`。

客户端中打开 `/kasuga_effects`，点击 `Parameters` 即可进入由 schema 自动生成的参数面板。
面板不识别某个具体效果：所有公开了参数的 shader 都会自动出现。Boolean 显示为开关，其余类型
按分量显示滑条；向量、颜色和矩阵会使用相应的分量名。修改在下一次 draw 时生效，不触发 shader
重新生成或重新编译。默认参数块异步保存在
`config/kasuga_lib/shader-parameters.json`，客户端重启后会按 shader ID 和参数名恢复；独立的
`createParameterBlock()` 是 effect 实例状态，不会写入全局配置。配置文件只保存相对 schema
默认值的 override；执行 reset 后，未来版本修改的默认值仍可正常生效。代码可通过
`isDefault(name)` 和 `hasOverrides()` 判断当前状态。

客户端也可用以下命令检查、修改和恢复任意 shader 主动公开的参数。命令适合滑条无法精确表达的
大范围整数，或需要一次输入完整 vector/matrix 的场景：

```text
/kasuga_effects parameter list <shader-id>
/kasuga_effects parameter set <shader-id> <name> <value...>
/kasuga_effects parameter reset <shader-id> <name>
```

多个 vector/matrix 分量可用空格或逗号分隔。内建黑洞示例公开 `LensingScale`、
`DiskBrightness` 和 `ChromaticScale`，其余屏幕、时间和 per-effect packed uniforms 保持内部。

Fullscreen shader 可以直接由 Java 表达式构建并注册：

```java
ShaderProgram program = ShaderProgram.fullscreen("example:screen_tint", shader -> {
    var scene = shader.sampler2D("SceneSampler");
    var tint = shader.uniformVec3("Tint", 1.0f, 1.0f, 1.0f);
    var source = scene.sample(shader.texCoord());
    shader.fragmentColor(shader.vec4(source.rgb().mul(tint), source.a()));
});

ShaderRegistration shader = pipelines.shader(
        RenderShaderDescriptor.generated(program)
                .withPreload(ShaderPreloadPolicy.DEFERRED, 50)
);

shader.whenReady().thenAccept(status -> rebuildDependentResources(shader.handle()));
```

## 从 GLSL 迁移到 Java Shader DSL

Builder lambda 对应 GLSL 的 `main()` 函数体。Lambda 执行时不会计算像素结果，而是按调用顺序
记录声明、表达式和语句，最后生成 GLSL 150。

### 类型与表达式

Java DSL 使用表达式包装类型代替 GLSL 运算符。推荐用 `var` 保存中间表达式，具体类型仍由
Java 编译器检查。

| GLSL | Java DSL |
| --- | --- |
| `float`、`int`、`bool` | `FloatExpr`、`IntExpr`、`BoolExpr` |
| `vec2`、`vec3`、`vec4` | `Vec2Expr`、`Vec3Expr`、`Vec4Expr` |
| `mat2`、`mat3`、`mat4` | `Mat2Expr`、`Mat3Expr`、`Mat4Expr` |
| `sampler2D` | `Sampler2DExpr` |
| `struct Material` | `ShaderStructType`、`StructExpr` |
| `1.0`、`1`、`true` | `shader.f32(1)`、`shader.i32(1)`、`shader.bool(true)` |
| `vec2(x, y)` | `shader.vec2(x, y)` |
| `vec4(rgb, alpha)` | `shader.vec4(rgb, alpha)` |
| `a + b`、`a - b` | `a.add(b)`、`a.sub(b)` |
| `a * b`、`a / b` | `a.mul(b)`、`a.div(b)` |
| `a < b`、`a >= b`、`a == b` | `a.lt(b)`、`a.gte(b)`、`a.equalTo(b)` |
| `a && b`、`a || b`、`!a` | `a.and(b)`、`a.or(b)`、`a.not()` |
| `texture(image, uv)` | `image.sample(uv)` |
| `normalize(v)`、`length(v)` | `v.normalize()`、`v.length()` |
| `mix(x, y, t)` | `x.mix(y, t)` |
| `atan(y, x)` | `y.atan2(x)` |
| `matrix * vector` | `matrix.transform(vector)` |
| `left * right`（matrix） | `left.mul(right)` |
| `value.rgb`、`value.a` | `value.rgb()`、`value.a()` |
| `Values[index]` | `values.get(index)` |
| `float(integer)` | `integer.toFloat()` |

只有对应 `Expr` 类型实际暴露的方法可以生成。比如当前 `Vec2Expr` 支持 `normalize()`，并不保证
所有 vector wrapper 都已经暴露同样的函数。Java 数字不能直接参与大多数表达式时，使用
`f32()`、`i32()` 或对应方法的 primitive overload。

### Global、stage I/O 与输出

- `uniformFloat/Int/Bool/Vec*/Mat2/Mat3/Mat4` 对应 GLSL uniform；传入的值写入 Minecraft
  shader JSON 作为默认值。无默认值的 matrix overload 使用单位矩阵。
- `uniformFloatArray` 是当前唯一的 uniform array，长度必须大于 1，元素通过 `IntExpr` 索引。
- `sampler2D` 声明 `uniform sampler2D`。
- `inputFloat/Int/Vec*` 声明当前 stage 的 `in`。
- Vertex stage 使用 `outputFloat/Vec*` 声明 `out`，并用 `position(...)` 写入 `gl_Position`。
- Fragment stage 使用 `fragmentColor(...)` 写入生成器提供的 `fragColor`。
- Fullscreen fragment 可以调用 `texCoord()` 读取内建 vertex stage 的纹理坐标。
- Graphics fragment 必须用 `inputVec2` 等方法声明自己的输入；调用 `texCoord()` 会直接失败。
- Vertex program 至少要调用一次 `position(...)`，fragment program 至少要调用一次
  `fragmentColor(...)`。当前校验只确认 IR 中存在写入，不分析所有分支是否都会写入，完整覆盖
  所有运行路径由 shader 作者保证。

Graphics program 中的两个 stage 分别对应传统 `.vsh` 和 `.fsh`：

```java
ShaderProgram particle = ShaderProgram.graphics(
        "example:particle",
        vertex -> {
            var position = vertex.inputVec3("Position");
            var uv = vertex.inputVec2("UV0");
            var color = vertex.inputVec4("Color");
            var modelView = vertex.uniformMat4("ModelViewMat");
            var projection = vertex.uniformMat4("ProjMat");

            vertex.outputVec2("particleUv", uv);
            vertex.outputVec4("particleColor", color);
            vertex.position(projection.transform(modelView.transform(
                    vertex.vec4(position, vertex.f32(1))
            )));
        },
        fragment -> {
            var uv = fragment.inputVec2("particleUv");
            var color = fragment.inputVec4("particleColor");
            var radial = fragment.f32(1).sub(
                    uv.sub(fragment.vec2(0.5f, 0.5f)).length().mul(2).min(1)
            );
            fragment.fragmentColor(fragment.vec4(
                    color.rgb().mul(radial), color.a().mul(radial)
            ));
        }
);

RenderShaderDescriptor descriptor = RenderShaderDescriptor.generated(
        particle,
        DefaultVertexFormat.POSITION_TEX_COLOR
);
```

`Position`、`UV0`、`Color` 等 input 名称和类型必须与传入的 Minecraft `VertexFormat` 对应。
DSL 会校验 vertex/fragment 之间的接口，但不会反查 `VertexFormat`。

### 局部变量与控制流

普通 Java 变量只持有一个 IR 表达式。下面的 `brightness` 不会生成 GLSL local，而是在使用点
内联：

```java
var brightness = color.rgb().mul(intensity);
shader.fragmentColor(shader.vec4(brightness, color.a()));
```

`letFloat/letVec*`、`letMat*` 和 `letStruct` 会生成只在 DSL 中读取的 GLSL local。需要后续赋值
时使用对应的 `local*`，通过 `get()` 读取、`set()` 写入。

以下 GLSL：

```glsl
float sum = 0.0;
for (int index = 0; index < Count; ++index) {
    if (index >= 8) break;
    if (Values[index] <= 0.0) continue;
    sum = sum + Values[index];
}
fragColor = vec4(sum, sum, sum, 1.0);
```

对应的 Java DSL 为：

```java
var count = shader.uniformInt("Count", 0);
var values = shader.uniformFloatArray("Values", 8, 0.0f);
var sum = shader.localFloat("sum", shader.f32(0));

shader.forRange("index", shader.i32(0), count, index -> {
    shader.ifThen(index.gte(shader.i32(8)), shader::breakLoop);
    shader.ifThen(values.get(index).lte(shader.f32(0)), shader::continueLoop);
    sum.set(sum.get().add(values.get(index)));
});

shader.fragmentColor(shader.vec4(sum.get(), sum.get(), sum.get(), shader.f32(1)));
```

- `ifThen` 和 `ifThenElse` 生成 shader 分支；普通 Java `if` 只在构建 IR 时执行一次。
- `forRange(name, start, end, body)` 生成递增 1、结束条件为 `< end` 的 int loop。
- `whileLoop(condition, body)` 和 `doWhile(condition, body)` 分别生成 `while` 与 `do while`。
- `breakLoop()` 只能退出当前最内层 loop；`continueLoop()` 可在 loop 内使用。
- 普通 Java `for` 可以用于按常量展开表达式，相当于手工 unroll，不会生成 GLSL loop。
- Java lambda 中需要跨分支或循环修改的 shader 值必须使用 `ShaderVariable`，不能通过重新绑定
  Java 局部变量表达 shader assignment。

```java
var cursor = shader.localInt("cursor", shader.i32(0));

shader.whileLoop(cursor.get().lt(count), () -> {
    cursor.set(cursor.get().add(1));
    shader.ifThen(cursor.get().gte(shader.i32(64)), shader::breakLoop);
});

shader.doWhile(cursor.get().gt(shader.i32(0)), () ->
        cursor.set(cursor.get().sub(shader.i32(1)))
);
```

### Struct

Struct 是命名类型。字段 key 由创建它的 `ShaderStructType.Builder` 持有；即使另一个 struct 有同名
同类型字段，也不能混用。先声明被依赖的 struct，再声明包含它的 struct。当前 struct 用于 typed
local、构造、字段读取和字段赋值，不作为 uniform 或 stage input/output。

```java
var materialBuilder = ShaderStructType.builder("Material");
var tint = materialBuilder.vec3Field("tint");
var roughness = materialBuilder.floatField("roughness");
var materialType = materialBuilder.build();

shader.declareStruct(materialType);
var material = shader.localStruct("material", shader.structValue(
        materialType,
        shader.vec3(1.0f, 0.8f, 0.6f),
        shader.f32(0.5f)
));

shader.setStructField(material, roughness, shader.f32(0.25f));
var finalTint = material.get().field(tint);
```

`structValue` 的参数按字段声明顺序传入，并在 Java 构建期检查数量和类型。

### Switch

`switchOn` 接受 `IntExpr`。`caseOf` 保持声明顺序，不自动插入 `break`，因此和 GLSL 一样允许
fallthrough；需要结束 case 时显式调用 `breakSwitch()`。`defaultCase` 最终输出在所有 case 后面。

```java
shader.switchOn(mode, cases -> cases
        .caseOf(0, () -> {
            intensity.set(shader.f32(0.25f));
            shader.breakSwitch();
        })
        .caseOf(1, () -> intensity.set(shader.f32(0.75f))) // fallthrough
        .defaultCase(() -> intensity.set(shader.f32(1.0f)))
);
```

`breakLoop()` 和 `breakSwitch()` 都要求对应结构是最内层 break target，避免生成语义错误的裸
`break`。例如 switch 位于 loop 内时，case 中可以 `breakSwitch()`，不能直接 `breakLoop()`；需要
退出外层 loop 时应设置一个 bool local，并在 switch 结束后判断它。

### 命名与链接规则

- Shader program ID 必须使用小写 `namespace:path`。
- Shader identifier 必须匹配 `[A-Za-z_][A-Za-z0-9_]*`，不能以 `gl_` 开头，也不能使用 GLSL
  保留字。
- 同一 stage 中的 global、local 和 loop index 共用一套名字，整个 stage 内不能重复；不同代码
  block 不会创建可重复使用名称的独立命名域。
- Fragment input 必须存在同名同类型的 vertex output。Vertex 可以声明 fragment 未读取的额外
  output。
- 两个 stage 复用同名 uniform 或 sampler 时，storage、类型、array 长度和默认值必须完全
  一致。
- 名称区分大小写，并直接成为最终 GLSL 和 Minecraft JSON 中的名称。

### Runtime uniform 与 sampler

- 不要长期缓存 `ShaderInstance`；F3+T 会替换实例。长期对象只保存
  `RenderShaderHandle`，每次渲染通过 handle 或 pass context 取得当前 generation。
- Uniform 在 render thread 上通过当前 `ShaderInstance.safeGetUniform(name).set(...)` 更新。
- Fullscreen pass 使用 `colorSampler`、`depthSampler` 或 `textureSampler` 绑定 sampler。
- Float array 以完整 `float[]` 上传；数组容量和 shader 声明保持一致。
- DSL 中的 uniform 默认值只用于生成 JSON，不会覆盖每帧由 Java 上传的当前值。

```java
context.fullscreen(PostProcessGraphTarget.main(), shaderHandle)
        .colorSampler("SceneSampler", context.read(scene))
        .uniforms(active -> active.safeGetUniform("Tint").set(red, green, blue))
        .draw(false);
```

### Raw GLSL escape

Typed API 无法表达的 GLSL 可以通过 raw escape 接入。所有 raw 入口都标有
`@DelicateShaderApi`：它是代码审查信号，不是稳定性承诺，也不会让 Java 编译器验证 GLSL。

```java
@DelicateShaderApi
static ShaderProgram derivativeShader() {
    return ShaderProgram.fullscreen("example:derivative", shader -> {
        shader.rawPreamble("#extension GL_ARB_gpu_shader5 : enable");
        shader.rawDeclaration("""
                float saturate(float value) {
                    return clamp(value, 0.0, 1.0);
                }
                """);

        var edge = shader.rawFloat("dFdx(texCoord.x)");
        shader.rawStatement("if (edge < 0.0) discard;");
        shader.fragmentColor(shader.vec4(edge, edge, edge, shader.f32(1.0f)));
    });
}
```

- `rawPreamble` 插入在 `#version 150` 后，适合 extension 和宏。
- `rawDeclaration` 插入在 globals 后、`main()` 前，适合用户函数和 backend-specific 声明。
- `rawStatement` 在当前 block 原样生成语句。
- `rawFloat/Int/Bool/Vec*/Mat*/Struct` 把源码包装成调用方声明的表达式类型。

Raw API 只拒绝空字符串，不解析源码，也不跟踪其中的类型、标识符、uniform、stage interface 或
控制流。传给 `rawFloat` 的表达式是否真为 float、raw uniform 是否进入 Minecraft JSON，以及
目标驱动是否接受相关 GLSL，都由调用方负责。能用 typed API 表达时优先使用 typed API。

Typed API 目前仍未暴露用户函数、struct uniform/stage I/O、非 float uniform array、任意 swizzle、
`discard`、derivative 和高级 texture sampling；这些能力可谨慎使用 raw escape，或者通过资源
shader/custom factory 接入。若某项能力需要跨 backend、链接校验或长期公共 API，应继续扩展
typed IR 和 backend，而不是固化 raw 字符串。

## Gradle shader 生成

```groovy
plugins {
    id 'builder.kasuga.shader'
}
```

- `builder.kasuga.shader` 插件创建 `shader` source set，读取 `src/shader/java` 和
  `src/shader/resources`，并为其加入 shader DSL 依赖。
- `generateKasugaShaders` 在 `processResources` 前执行，先清空自己的生成目录，再执行 shader
  providers。
- Provider 必须是实现 `ShaderProgramProvider` 的非抽象 public 类，并具有 public 无参构造器；
  不满足条件时生成任务失败。
- Provider 按 class name 排序后执行。其返回的 program 被转换成 `.vsh`、`.fsh` 和 `.json`
  资源。
- 两个 program 生成同一路径时任务失败，不覆盖已有生成结果。
- 生成目录自动加入 main resources，因此生成内容会进入最终 jar。

## Managed effect

- `EffectRenderPipeline` 负责 effect instance 的 tick、存活检查、视锥剔除、可选远到近排序和
  renderer 调用。
- 客户端未暂停且世界存在时，每个活动 effect 每个 client tick 更新一次。
- Effect 在 tick 前后返回非存活状态时会被移除。Tick 抛出运行时异常时只移除该 instance，并
  记录错误。
- Render traversal 使用插值后的 bounds 做视锥剔除。启用排序时按插值位置到相机的距离从远到
  近排列；每个可见 effect 每帧最多计算一次距离。高频 effect 可以覆写
  `distanceToSqr(partialTick, observer)`，避免仅为排序创建临时位置对象。
- 至少存在一个可见 instance 时才调用 renderer 的 `begin` 和 `end`，两者每条 pipeline 每帧
  最多各调用一次。
- 单个 instance 的 render 异常会被记录，并继续处理其他 instance；它不会自动移除该 instance。
- `spawn()`、`clear()` 和 `EffectHandle.remove()` 可以跨线程调用。列表变更在下一次 client tick
  或 render traversal 时应用。
- `EffectHandle.remove()` 只在第一次成功移除时返回 `true`。
- 客户端切换或卸载世界时，所有 effect pipeline 清空现有 instance，同时清空 post-process
  target pool。客户端暂停期间不执行 tick。
- Pipeline 关闭后不再接受新 instance，也不再参与 tick 或 render。

同一种 effect 共用 renderer 和 pipeline state，每个实例由独立 handle 控制：

```java
RenderPipelineDescriptor sparkDescriptor = RenderPipelineDescriptor.builder(
        ResourceLocation.fromNamespaceAndPath("example", "sparks"),
        RenderPhase.AFTER_PARTICLES
).priority(110).build();

EffectRenderPipeline<SparkEffect> sparks = pipelines.effects(
        sparkDescriptor,
        true,
        new SparkEffectRenderer()
);

EffectHandle<SparkEffect> spark = sparks.spawn(new SparkEffect(position));
spark.remove();
```

## Post-processing pass

- Post-process pass 必须使用语义 `RenderPhase.POST_PROCESS`；其他 placement 在注册时被拒绝。
- Pass 始终在 render thread 上执行，并在结束或抛出异常后重新绑定 main render target。
- Target descriptor 支持相对屏幕尺寸或固定尺寸、可选 depth attachment、nearest/linear filter
  和 clear color。解析后的宽高至少为 1 像素。
- Target pool 按物理 ID 复用 target。Descriptor 或解析尺寸变化时会销毁并重建原 target。
- Color copy 不允许 source 与 destination 相同。Depth copy 要求两端都有 depth attachment。
- Fullscreen draw 只接受 `BLIT_SCREEN` shader，会备份并恢复 GL state。
- Fullscreen draw 不允许从当前 output target 采样 color 或 depth，以避免 framebuffer feedback。
  直接绑定的外部 texture ID 由调用方保证 feedback 安全。

## Post-processing graph

- Graph ID 必须与注册它的 pipeline descriptor ID 相同。
- Graph build 时拒绝重复 target、重复 pass、未声明 target、缺少 producer 的 managed-target
  读取、managed target 的多个 writer、缺失的显式依赖和依赖环。
- 每个 pass 必须声明至少一个输出，且同一 pass 不能同时读取和写入同一 target。
- Main target 是外部 target，可以被多个 pass 写入；其他 target 是 graph 管理的 target。
- Pass 顺序首先满足显式 `after` 和由读写关系推导的依赖。在所有当前可执行的 pass 中，
  priority 较小者先执行，priority 相同时按 pass ID 排序。
- `prepare` 在 managed target 分配和 pass 执行之前运行。返回 `false` 时该帧不分配 target，也不
  执行任何 pass。
- 每次 graph 执行都创建新的 `PostProcessGraphFrame`。其中的数据只在本次 prepare 和后续
  pass 之间共享；按声明类型读取，缺失或类型不符时抛出异常。
- Pass context 只允许读取和写入该 pass 已声明的 target；未声明访问在运行时失败。
- 不同 graph 的逻辑 target 会转换为包含 graph ID 的物理 ID，因此相同逻辑名称不会互相
  冲突。物理 ID 和替换后的 target descriptor 在 graph build 时计算，执行阶段直接复用。
- Managed target 按 graph registration 引用计数；最后一个使用者关闭后在 render thread 释放。
- Prepare 或 pass 异常会附加 graph/pass ID 后向上抛出，随后由 world pipeline dispatcher 记录，
  不阻止后续 pipeline。
- `raw()` 是绕过 graph 资源访问限制的底层出口；使用后资源声明和 feedback 安全由调用方负责。

下面的 graph 先保存主画面，再用 fullscreen shader 合成回主 target：

```java
ResourceLocation graphId = ResourceLocation.fromNamespaceAndPath("example", "screen_tint");
PostProcessGraphTarget scene = PostProcessGraphTarget.managed(
        ResourceLocation.fromNamespaceAndPath("example", "scene")
);

PostProcessGraph graph = PostProcessGraph.builder(graphId)
        .target(PostProcessTargetDescriptor.builder(scene.id())
                .screenScale(1.0f)
                .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR)
                .build())
        .pass(PostProcessGraphPassDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath("example", "capture"),
                        context -> context.copyColor(PostProcessGraphTarget.main(), scene, true)
                )
                .readsMain()
                .writes(scene)
                .build())
        .pass(PostProcessGraphPassDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath("example", "composite"),
                        context -> context.fullscreen(PostProcessGraphTarget.main(), shader.handle())
                                .colorSampler("SceneSampler", context.read(scene))
                                .draw(false)
                )
                .reads(scene)
                .writesMain()
                .build())
        .build();

PostProcessGraphRegistration graphRegistration = pipelines.graph(graph, 100);
```

## 诊断与参数界面

- `/kasuga_effects` 打开客户端诊断与参数界面；它不会暂停游戏。
- Effects 页面显示 owner、pipeline ID、effect 类型、活动数、可见数和最近渲染耗时。
- Shaders 页面显示 owner、来源、状态、策略、priority、origin、generation、排队等待、
  preparation/compile 耗时、cache 命中、公开参数数量和失败信息。
- Shader 汇总同时显示 preparation executor 的请求/实际 worker 数、可用 CPU，以及 render-thread
  preload scheduler 的队列状态。
- `Parameters` 打开通用 schema 参数编辑器；它不会为内建效果或黑洞示例添加专用分支。
- Pipelines 页面显示 owner、placement、priority、draw mode、buffer size 和已缓存
  `RenderType` 变体数量。
