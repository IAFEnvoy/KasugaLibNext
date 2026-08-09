# 状态机（FSM）设计与 API 审查

> 审查对象：`modules/modelling` 的 `lib.kasuga.rendering.models.uml.dynamic.fsm` 全家桶 + `modules/scripting` 的 `lib.kasuga.scripting.fsm`。
> 分块进行，每块独立成节。审查基于分支 `state-machine` 的代码（纯 Java，主代码约 5300 行）。

## 分块计划

1. **核心引擎** — `StateMachine` / `Layer` / `State` / `Transition` / `Blender` / `Pose` ✅
2. **类型化变量存储** — `state/`（`StateVar` / `StateVarType` / `StateMap` / 触发器语义）✅
3. **数据驱动定义** — `codec/` + `DefinitionStateMachineFactory` + `FsmFunctionLibrary`
4. **方块绑定** — `FsmBlock` / `AnimationBlockEntity` / `FsmBlockEntityFactories` / cap + `MachineQuery`
5. **脚本绑定** — `scripting/fsm`（`AnimatorApi` / `AnimatorBuilderApi` / 值封送）
6. **网络同步** — `sync/`（快照、去重、陈旧性校验）
7. **渲染绑定** — `PoseSink` / `ModelInstancePoseSink` / `KasugaModelPipelines`
8. **横切问题** — `GLOBAL` 单例 vs 宿主自有注册表、菜单/GUI 绑定缺失、脚本定义覆盖资源定义

---

## 第 1 块：核心引擎

### 做得好的地方

- **`Owner` 泛型 + `StateContext` record** 是干净的设计：`ctx.owner()` 全程类型安全无强转，context 按 layer/state 作用域，结构读取（`stateElapsedTicks()` 等）不需要字符串路径。
- **句柄式 DSL**（`transition(id, from, to)` 用 `State` 句柄而非字符串）编译期安全。
- **`Blender` 的语义分层清晰**：`BlendMode`（层间合成）与 `ApplyMode`（骨骼最终写入）两个正交轴分开，javadoc 把边界讲得很明白。
- **`Pose` 不可变 + `Transform` 防御性拷贝**，`Pose.empty()` 是常量，没有明显的别名陷阱。

### 缺陷与改进建议（按严重程度）

**1. 构建期零校验，多处静默错误（最重要）**

- `Builder.build()`（`StateMachine.java:377`）不校验任何东西：重复 layer id 会静默覆盖 `layersById`；`Layer.state()` 允许重复 state id。
- **`Layer.transition(id, from, to)` 从不校验 `from`/`to` 属于本层**（`Layer.java:72`）。如果传了另一层的 state，`transitionsByFrom.get(active)` 永远匹配不到——转换成为永远不会触发的死代码，无任何警告。
- 建议：`build()`/`start()` 时做一次 `validate()`，对重复 id、跨层引用 fail-fast 或至少 `LOGGER.warn`。

**2. 混合时钟：`dt`（秒）与 `stateElapsedTicks`（固定步进）语义矛盾**

- `tick(float dt)`（`StateMachine.java:126`）签名暗示可变步长，但 `Layer.tick` 里 `stateElapsedTicks++` 与 `dt` 无关（`Layer.java:286`），`durationSeconds()` 也只是写死乘 20（`State.java:43`）。
- 后果：宿主若以非 1/20 的 dt 调用（比如渲染线程插值），cross-fade 用真实秒数走、duration 却按调用次数走，两者漂移。
- 建议：要么把契约收窄为「固定 20Hz 逻辑时钟，dt 仅供 cross-fade」并在 javadoc 写死，要么 duration 也改为按秒累计。当前是半吊子。

**3. 触发器无缓冲（latch），`whenComplete + on(trigger)` 组合有时序陷阱**

- `Transition.fires`（`Transition.java:109`）要求 trigger 与 sourceComplete **同一 tick** 成立。trigger 是 tick 级 ephemeral——若 trigger 先于 duration 完成到达，它被 `removeEphemeral()` 清掉，转换永远不触发，状态死锁在已完成状态。
- 经典状态机一般提供「trigger 挂起直到被消费」的选项。建议：增加 `onBuffered(trigger)` 或在 `whenComplete` 转换上自动锁存 trigger。

**4. 每 tick 的分配热点与死代码**

- `StateMachine.tick` 每次 `new Blender()`（`StateMachine.java:127`），但 `Blender.reset()`（`Blender.java:155`）存在却**无人调用**——明显的死代码，说明曾打算复用。
- cross-fade 期间 `Layer.blend()`（`Layer.java:358`）每 tick 建 3 个 `HashSet` + `Pose.Builder` + N 个 `Morph`/`Bone` record（每个 `Bone` 还 copy Transform）。
- 项目已有 JMH benchmark（`StateMachineBenchmark`），建议：复用 `Blender` 实例、给 cross-fade 做缓存（alpha 不变时跳过重建）。

**5. 可变内部状态泄漏**

- `layers()` 返回可变内部 `ArrayList`（`StateMachine.java:67`），javadoc 自己承认绕过索引的副作用，迫使 `layerOrNull` 保留线性回退扫描。应返回 `Collections.unmodifiableList`，需要动态加层就提供显式 `addLayer()` 并维护索引。
- `Layer.start()` 在 `Builder.layer()` 注册时调用（`StateMachine.java:361`），但调用方仍可持有 `Layer` 引用继续 `state()`/`transition()`——此时 `transitionsByFrom` 已固化为 unmodifiable，新加的转换静默失效。构造期/运行期边界需要「sealed」语义。

**6. `goTo` 语义不一致且是硬切**

- 三个入口：`StateMachine.goTo(String, String)`、`Layer.goTo(State)`、`StateContext.goTo(State)`，全走同一个单槽 `pendingGoTo`——同 tick 两次调用后者静默覆盖前者。
- `forceEnter`（`Layer.java:308`）清掉 in-flight transition 且**无 cross-fade 能力**，脚本/网络驱动的 `goTo` 会造成动画硬跳。建议给 `goTo` 加可选 crossFade 参数。
- `pendingGoTo` 生效时机（下一次 `tick`）对外部调用者（脚本/网络）不明显，值得在 javadoc 里写清。

**7. `lockLayer` 用字符串 id，且只增不减**

- 整个 DSL 都是类型安全句柄，`lockLayer(String, int)`（`StateContext.java:140`）却退回字符串——拼写错误静默无效。
- `locks.merge(id, ticks, Integer::max)`（`StateMachine.java:176`）意味着无法缩短或解除锁。建议提供 `unlockLayer` / `lockLayer(Layer, int)` 重载。

**8. `version` 的语义边界模糊**

- `version++` 只在状态切换时发生；`vars` 变化、weight/mode 变化都不 bump。而 `StateMachineSnapshot` 也不含 vars——这意味着**纯变量变化不会触发网络同步**，server/client 的 vars 会分叉（条件求值依赖 vars 时行为不一致）。可能是刻意设计，但必须明确文档化，否则是同步块的隐藏 bug 源。→ 第 6 块核实 `FsmSyncDedup` 是否依赖 version。

**9. 小异味**

- `FrameAccum.applyBase(frame, priority)` 的 `priority` 永远传 0（`Blender.java:199`）——死参数，要么实现 BASE 层间优先级要么删掉。
- ADDITIVE morph 在 `value()` 里 `clamp01(baseValue * baseFactor + additiveSum)`（`Blender.java:47`）：additive 叠加后被钳到 [0,1]，溢出信息丢失。morph 作为权重或许合理，但应文档化这是有意钳制。
- `StateMachine` 没有 `dispose()`/生命周期终点，长期存在的宿主（BE 卸载）只能靠 GC。

### 小结

引擎的**骨架设计是好的**（泛型 owner、scoped context、正交混合轴、句柄 DSL），问题集中在**契约的严密性**：构建期不校验、时钟语义模糊、trigger 无缓冲、可变内部状态泄漏。这些不修，上层每个绑定块（脚本、方块、同步）都会继承这些陷阱。

---

## 第 2 块：类型化变量存储（`state/` 包）

### 做得好的地方

- **DataComponent 类比落地得很准**：`StateVar<T>` 自带 codec / default / validator / ephemeral 标记，`StateMap.get` 的类型安全靠构造保证（`set` 只存 var 自身类型的值），调用点零强转。这套接口对 Java / JSON / 脚本三端都友好。
- `defaultValue` 强制非 null 且构造时过 validator（`StateVar.java:51`），杜绝了"默认值本身非法"。
- `StateVarRegistry.register` 对不兼容重注册打 warn（`StateVarRegistry.java:36`），比静默覆盖强。

### 缺陷与改进建议（按严重程度）

**1. id-only 相等性会破坏「类型安全靠构造」的核心承诺（最重要）**

- `StateVar.equals/hashCode` 只看 `ResourceLocation`（`StateVar.java:83-96`）。于是两个同 id 不同 `T` 的 var 在 `MutableStateMap` 里是**同一个槽位**：`set(intVar, 1)` 之后 `get(boolVar)` 命中同一 key，unchecked cast 在**调用点**抛 `ClassCastException`——而 javadoc 明确承诺 "get never needs a runtime type check"（`StateVar.java:19`）。承诺不成立。
- 这个冲突不只在理论里：`StateVarRegistry` 重注册时旧机器仍持有旧 var 实例，id 相等意味着旧实例照样命中新类型的槽位。
- 建议：`MutableStateMap.set` 时若槽位已存在且 key 的 `type()` 不同，fail-fast 抛异常（把错误从 get 的调用点提前到 set 的肇事点）；或把 `type` 纳入 equals。至少要修正 javadoc 的承诺。

**2. 可变默认值被共享，`get` 返回的就是默认值本体**

- `MutableStateMap.get` 在值缺失时直接返回 `var.defaultValue()`（`MutableStateMap.java:31`）。对 `VEC3` 这类可变类型（`Vector3f`），调用方拿到的是**共享的默认实例**，一改就污染所有机器。
- `zeroDefault()` 每次 new 一个新 `Vector3f`（`StateVarType.java:91`）只保护"声明时"，保护不了"读取时"。
- 建议：契约上要求默认值不可变并在 `StateVar` 构造时声明；或对可变类型在 get 时防御性拷贝。

**3. 触发器缺少专用工厂，存在"默认 true"自杀写法**

- `ephemeral()` 标记可以加在任何类型上，`StateMachine.trigger()` 只接受 `StateVar<Boolean>`，但没有任何机制阻止写出 `defaultValue(true)` 的 ephemeral bool——这种 var 会让 `isTriggered` **每 tick 都真**，转换无限连发。
- 建议：提供 `StateVar.trigger(ResourceLocation)` 专用工厂（bool + ephemeral + 强制 default false + 强制 validator 拒绝 true 默认），让正确写法成为最短路径。

**4. `StateMap` 无法枚举「已声明但未设置」的 var**

- `keySet()` 只含显式设置的 var（`MutableStateMap.java:48`）。调试面板 / 同步 / 脚本 introspection 想要列出"这台机器有哪些 var（含默认值）"时无从下手——机器不知道自己声明过哪些 var，声明信息散落在各 var 实例和 registry 里。
- 建议：`StateMachine` 增加 `declaredVars()`（builder/definition 构建时收集），或至少把该限制文档化。

**5. 线程安全没有说法**

- `MutableStateMap` 用裸 `HashMap`（`MutableStateMap.java:19`）。server tick 线程、`FsmSyncClient`（据称在 main thread enqueue）、脚本引擎（Javet/V8 的调用线程）三方都可能读写 vars。目前没有文档说明 vars 的线程契约。
- → 第 5、6 块核实脚本与同步的实际调用线程后再定级；先把风险记下。

**6. 小异味**

- `StateVarRegistry.compatible()` 只比 type 和 default（`StateVarRegistry.java:41`），重注册改 `ephemeral` / `validator` 不告警——而 ephemeral 语义变化恰恰最危险。
- `streamCodec` 字段整条链路无人消费（`StateVar.java:138` 自述 reserved）。保留可以，但建议加 `@ApiStatus.Internal`/注释说明"设置它目前没有任何效果"，避免使用者误以为配了就会同步。
- `StateVarType` 是封闭目录（6 种），`byToken` 对未知 token 直接抛（`StateVarType.java:54`）。Java 端可用自定义 Class+Codec 绕过，但 JSON 端被封死——数据驱动块的扩展性上限就在这。建议 `byToken` 的异常信息列出合法 token。
- `StateMap.getOptional` 的语义（有设置→of(值)，无设置→empty，**不回落默认值**）是对的，但与 `get` 的"永远非 null"放在一起容易误用，javadoc 已写清，保持。

### 小结

这块的抽象选型（typed key + 默认值内嵌 + ephemeral 标记）是整套 FSM 里**最成熟的部分**，但 id-only 相等性与"类型安全靠构造"之间存在一条真实的裂缝（问题 1），加上可变默认值共享（问题 2），都是从"设计正确"滑向"运行时事故"的典型路径。修这两处成本很低，收益是契约重新变得可论证。

---

## 第 3 块：数据驱动定义（`codec/` + `DefinitionStateMachineFactory` + `MachineRegistry` + loader）

### 做得好的地方

- **缺失引用「降级 + 聚合告警」而非静默或崩溃**：`reportMissingReferences`（`DefinitionStateMachineFactory.java:263`）把整台机器的缺失行为引用聚成一条 warn，condition 降级为 false、action 降级为 no-op。对数据包作者友好。
- **定义与实例分桶**（`MachineRegistry`）：资源重载只清 RESOURCE 定义桶，存活机器不被摧毁，重载语义在 javadoc 里写得很清楚。
- `StateVarDefinition` 的 passthrough codec 把 default 的解码推迟到工厂按类型进行——务实的做法。
- 定义带 `DefinitionSource` 溯源（RESOURCE / SCRIPT），为覆盖语义提供了依据。

### 缺陷与改进建议（按严重程度）

**1. 工厂边界把旗舰级类型安全丢光了：`StateMachine<Object>`**

- `DefinitionStateMachineFactory.build` 固定返回 `StateMachine<Object>`（`DefinitionStateMachineFactory.java:69`），`FsmMachineBuilder` 同样。于是所有数据驱动机器的 `ctx.owner()` 都是 `Object`——`FsmCondition`/`FsmAction` 里必须强转 owner。第 1 块夸过的「全程无强转」恰恰在数据驱动这个最主要的入口被放弃。
- 建议：工厂改泛型 `<O> StateMachine<O> build(O owner, ...)`，`FsmCondition`/`FsmAction` 参数化为 `FsmCondition<O>`。改动是纯泛型传播，零运行时代价。

**2. 结构引用错误静默，与行为引用的告警策略不一致**

- `initial_state` 指向不存在的状态：`stateById.get(...)` 得 null，`Layer` 静默回退到 `states.get(0)`（`DefinitionStateMachineFactory.java:155` + `Layer.start()`）——数据 typo 直接改变行为，无任何日志。
- transition 的 `from`/`to` 未解析：`continue` 跳过（`DefinitionStateMachineFactory.java:160-162`），连 warn 都没有。
- 行为引用有聚合告警，结构引用却是静默的——同一文件里两套标准。建议把结构校验也并入 `reportMissingReferences`（或改名 `validateDefinition`），unknown initial/from/to 至少 warn。

**3. inline var 的注册时机错位 + 重载泄漏**

- `resolveInline` 在**每次 build()** 时把匿名 var 注册进 `StateVarRegistry.GLOBAL`（`DefinitionStateMachineFactory.java:122`）。N 个方块实体各自 build → 同一批 var 被重复注册 N 次；进程级 registry 的状态取决于"谁实例化过"，而不是"加载了什么"。
- 资源重载时 `clearResourceDefinitions()` 清机器定义，但**没有任何人清 inline var**：机器定义从数据包删除后，它的 var 永远留在 GLOBAL（泄漏）。
- 建议：inline var 注册挪到 **definition 加载期**（loader 或定义注册时），并在 `clearResourceDefinitions` 的同一事务里按 namespace 清理。var 生命周期应与定义一致。

**4. 非 ephemeral 的 bool var 可作 `trigger_on`，是数据作者的脚枪**

- `resolveTrigger` 只检查类型是 Boolean（`DefinitionStateMachineFactory.java:216`），不检查 `ephemeral`。数据作者声明一个普通 bool var 当 trigger 用：一旦置 true 且无人复位，转换**每 tick 连发**。
- 建议：trigger var 非 ephemeral 时 warn（或直接强制按 ephemeral 处理），与第 2 块的 `StateVar.trigger()` 工厂配套。

**5. `definitionVersion` 作为同步身份检查过于粗糙**

- 计数器是**全局单调**的：任何一次资源重载、任何一次脚本重注册（包括幂等重注册，`MachineRegistry.java:75-78`）都 bump 它。作为「定义身份」检查时，无关定义的变化会导致所有机器的身份校验失败。
- 建议：改为按定义的内容 hash（或 per-id version）。→ 第 6 块核实 sync 的实际用法后定级。

**6. 小异味**

- `StateVarDefinition` 双模式（reference / inline）共享字段，codec 不阻止"同时给 reference 和 default"的矛盾输入，靠 javadoc 说「忽略」。可以在 codec 层 `validate`。
- `FsmMachineBuilder.build` 用「sink 非 null ⇒ clientSide」的隐式契约（`DefinitionStateMachineFactory.java:73`）——能工作，但客户端语义应显式传参。
- 空 layer（`states` 省略 → 空列表 + 必填的 `initial_state`）codec 能过、运行时 active 恒 null。codec 可加非空校验。
- 注解风格混用：`javax.annotation.Nullable`（loader）vs `org.jetbrains.annotations.Nullable`（其他文件）。
- `StateMachineDefinition` 无 schema 版本字段，后续格式演进没有抓手。

### 小结

数据驱动管线的**容错哲学是对的**（降级 + 告警 + 定义/实例解耦），但执行不一致：行为引用告警、结构引用静默；定义有重载语义、inline var 没有生命周期。最大的设计让步是工厂边界的 `Object` 泛型擦除——它把整个数据驱动生态（所有 Java 端 condition/action 作者）推回了强转时代，而修复只是泛型传播。

---

## 第 4 块：方块绑定（`FsmBlock` / `AnimationBlockEntity` / `FsmBlockEntityFactories` / cap + `MachineQuery`）

### 做得好的地方

- **生命周期清理做得完整**：`onChunkUnloaded`/`setRemoved` 三条路（client model / client sync / server sync）都解绑（`AnimationBlockEntity.java:316-329`），这是方块绑定最容易漏的地方。
- **模型 reload 自愈**：重绑只换 sink、不重建机器，状态保住（`AnimationBlockEntity.java:253-278`），思路正确。
- `FsmBlock` 与具体注册解耦（registry 扫描发现 BE 类型），ticker 有类型缓存 + instanceof 兜底。
- 每 BE 独立 `instanceLoc`，两个同型方块不共享 `ModelInstance`。
- warn-once（`definitionWarned`）避免定义缺失时刷日志。

### 缺陷与改进建议（按严重程度）

**1. 客户端机器既自跑逻辑又吃服务端快照，角色没有定义清楚（设计性问题）**

- `tickClient()` 每 tick 自跑 `m.tick()`（`AnimationBlockEntity.java:183`）——客户端机器会本地评估 guard、触发转换；同时 `FsmSyncClient` 又用服务端快照 conform 它。
- 问题：guard 读的 vars / owner 数据在两端不同（脚本只在服务端 set var、客户端 BE 数据不同步），客户端会做出服务端从未做的转换，心跳快照到达时被硬掰回去——**视觉抖动/状态拍打**的结构性强来源。
- 需要明确客户端机器的角色：是「完整逻辑的本地预测」还是「只播姿势的渲染傀儡」。现状是两者的混合物。建议：客户端机器默认**不评估 transition**（只按快照推进 + cross-fade 插值），或至少提供一个 `logicEnabled` 开关并在文档里写明预测模型的限制。→ 与第 6 块同步协议一起定案。

**2. 程序化 API 无持久化，世界重载即丢失**

- `stateMachine(id)` / `model(loc, name)` 是 public 程序化入口（`AnimationBlockEntity.java:80,90`），但类里**没有任何 NBT save/load**：世界重载后程序化赋值全部丢失，退回工厂闭包捕获的默认值。
- 同样，机器的运行时状态（active state、vars）也不持久化——服务端重启后所有机器回到 initial state。对纯装饰动画可接受，但 vars 可以承载准玩法状态，这个边界需要明确决策并文档化。
- 建议：至少持久化 `stateMachineId`/`modelLoc`/`modelName` 三个字段（几行 NBT 代码）；运行时状态是否持久化作为一个显式的设计决定写进文档。

**3. `FsmBlock.newBlockEntity` 每次创建都全量扫 BLOCK_ENTITY_TYPE 注册表**

- `newBlockEntity`（`FsmBlock.java:35-41`）对每次 BE 创建（放置、区块加载）遍历全部 BE 类型 + `isValid(state)`。ticker 路径有缓存，创建路径没有——同一个优化做了一半。
- 建议：像 `fsmBeType` 一样缓存解析结果（按 block state 或 block 实例），或改回标准的「注册时提供 BE 类型」模式（解耦目标可用 Supplier 注入实现）。

**4. INFO 级日志残留在每 tick/每事件路径上**

- `logVersionChange` 每次 version 变化打 INFO（`AnimationBlockEntity.java:190-197`，客户端）；模型 bind/unbind、sink 重绑也都是 INFO。FSM 方块多了之后日志被刷爆。这些都是开发期诊断日志，应降为 DEBUG/TRACE。

**5. `machine()` 的 synchronized 是虚假的安全感**

- `machine()` 加锁（`AnimationBlockEntity.java:108`），但同一个 `machine` 字段在 `tickClient`/`machineVersion`/`ensureClientModel` 里都是裸读。要么统一（字段 volatile + 构建加锁），要么承认单线程契约去掉 synchronized。现状是最差的组合：看起来线程安全，实际不是。

**6. 换 definition 时旧 sync key 泄漏**

- `stateMachine(id)` 换定义时只把 `machine = null`（`AnimationBlockEntity.java:80-87`），不解绑旧 `syncKey`（machineId 是 key 的一部分）——`FsmSyncClient`/`FsmSyncServer` 里旧 key 的 bind/dedup 记录残留。边缘情况，但与「生命周期清理完整」的自我定位矛盾。

**7. 小异味**

- `MachineQuery.machineAt` 走 capability 优先（`MachineQuery.java:24`），`machineOf` 却只 instanceof——同一辅助类两条路径标准不一。且既然 instanceof 兜底永远存在，为**全游戏所有 BE/entity 类型**注册 capability 的收益值得重新掂量（注册面 vs 实际解耦需求）。
- 「sink 非 null ⇒ clientSide」的隐式契约在这里已经造成了实打实的补丁：客户端逻辑机（模型未就绪）build 后需要手动 `setClientSide(true)` 修正（`AnimationBlockEntity.java:130-133`）。第 3 块建议的显式传参可以消掉这个 workaround。
- `SYNC_SERVER` 静态别名（`AnimationBlockEntity.java:48`）只是 `FsmSyncServer.GLOBAL` 的转发，存在的意义是让注释有地方挂——可直接用 GLOBAL。

### 小结

方块绑定的**工程完成度是高的**（生命周期、自愈、warn-once 都想到了），真正的缺口在两个设计决策上：客户端机器的角色未定义（与同步块的协议设计互为因果），以及程序化 API 无持久化。其余是收尾质量：日志级别、注册表扫描缓存、假线程安全。

---

## 第 5 块：脚本绑定（`AnimatorApi` / `AnimatorBuilderApi` / `StateValueCoercer` / `FsmApiRegistration`）

### 做得好的地方

- **边界纪律总体严明**：get/set/trigger 全线「coerce + warn-once + no-op」，不让 Java 异常随便穿过 JS 边界；warn-once 按 category+key 去重，既可见又不刷屏。
- `StateValueCoercer` 对 JS 数字的处理是认真想过的：Integer/Double 分派、整值 Double → int、拒绝 NaN/Infinity、vec3 支持数组和 `{x,y,z}` 两种形态。
- 结构读取是窄命名方法（`getState`/`getLayerMode`/...），没有把字符串路径语言暴露给脚本。
- 线程契约写在了 `instantiate` 的 javadoc 里（单线程 tick；宿主机器禁止走脚本 API tick）——而且**有实际强制**：宿主机器不进 `MachineRegistry.GLOBAL`，handle 根本解析不到。契约靠结构保证而不是靠自觉，这是好的。
- 引擎装配干净：`FsmApiRegistration.install` 两个全局挂到 `ScriptEngineType.Builder`，Javet 一行接入。

### 缺陷与改进建议（按严重程度）

**1. 同一定义无法实例化两次——handle 与 id 1:1 绑死（最大的隐藏语义限制）**

- `instantiate` 最后调 `registry.register(definition.id(), machine)`（`AnimatorBuilderApi.java:192`），而 `MachineRegistry.register` 对同 id 是**替换**：旧 handle 静默失效。
- 后果：脚本 `AnimatorBuilder.instantiate("kasuga_lib:foo", owner)` 调用两次，第一台机器的 handle 变死柄，之后所有 `Animator.get/set` 对它静默 no-op——脚本无从察觉。
- 「一台定义一台实例」对一个脚本引擎内的简单场景够用，但 API 形状（instantiate 返回新 handle）强烈暗示可以多实例。建议：实例按唯一 instance id 注册（handle 本身就是唯一键，byId 改为允许同 id 多实例或干脆去掉 byId），或在重复实例化时显式报错/返回旧 handle 并文档化。

**2. 错误哲学不一致：一半 no-op，一半抛异常**

- `AnimatorApi` 自述「never throws across the script boundary」（`AnimatorApi.java:30`），但同族的 `AnimatorBuilderApi.instantiate` 对未知定义**抛 `IllegalArgumentException`**（`AnimatorBuilderApi.java:180`），`registerDefinition` 对非法 JSON 也抛（`:143`），`registerCondition/Action/StateVar` 里的 `ResourceLocation.fromNamespaceAndPath` 对非法字符同样会抛。
- JS 侧拿到的是穿过 Javet 边界的 Java 异常，与「no-op + warn」的家族风格断裂。建议统一：边界方法全部不抛（返回 `""`/`0`/`-1` + warn），或者明确文档化「builder 抛、control 不抛」的两段式契约。

**3. `registerStateVar` 的幂等短路跳过兼容性检查**

- `if (stateVars.has(id)) return id`（`AnimatorBuilderApi.java:95-97`）——已存在就直接返回成功，**不看类型/default/ephemeral 是否一致**。脚本以为自己注册了想要的 var，实际拿到的是别人的定义，连 `StateVarRegistry.register` 里的不兼容 warn 都被绕过。
- 建议：短路前做 compatibility 检查，不一致时 warn（或拒绝）。

**4. 脚本机器与渲染、同步两个子系统都不通**

- 渲染：`instantiate(id, owner, model)` 要求脚本自己变出一个 `ModelInstance`——但脚本 API 里没有创建/绑定模型的路径，这个三参重载目前实际上够不着。
- 同步：脚本机器完全不在 `FsmSyncServer` 的世界里（那是 BE 驱动的），服务端的脚本机器状态到不了客户端。
- 也许都是刻意留白，但应在文档里写明「脚本机器 = 服务端逻辑机，暂无渲染/同步通道」，否则用户会按字面理解三参重载。

**5. `varType(handle, varId)` 的 handle 是死参数**

- 实现里根本没用到 handle（`AnimatorApi.java:127-134`），签名却要脚本传一个。改成 `varType(varId)`，或真的校验 handle。

**6. 小异味**

- `coerceVec3` 的 Map/List 路径对非数字元素静默 `asFloat → 0f`（`StateValueCoercer.java:97`）——标量类型遇到 wrong-kind 是 reject（返回 null），vec3 元素却是静默归零，标准不一。
- `warnedUnknown` 是无界 set：每种拼错的 var id 永久驻留。实践中量小，但理论上是个慢泄漏。
- `listVars` 只能列出显式设置的 var（继承第 2 块问题 4），脚本无法发现「已声明但未设置」的 var——`has()` 恒 false、`listVars` 不含它、`get()` 却返回默认值，这三个事实放在一起对脚本作者很困惑。
- `AnimatorApi.tick(handle, dt)` 把变步长 tick 暴露给脚本，叠加第 1 块的混合时钟问题（duration 按调用次数走），脚本用 `dt=0.5` 调 40 次和 `dt=1/20` 调一次的语义完全不同——边界上没有任何提示。

### 小结

脚本绑定的**边界工程是用心的**（coercion、warn-once、线程契约的结构化强制），问题集中在三处语义裂缝：实例模型是「一定义一实例」却长着多实例的脸（问题 1）、错误处理两套标准（问题 2）、注册短路跳过兼容性（问题 3）。前两个会直接造成脚本作者的静默故障。

---

## 第 6 块：网络同步（`sync/` + `StateMachineSnapshot`）

### 做得好的地方

- **事件驱动推送 + 强制心跳**的骨架是对的：版本去重（按 key×player）省带宽，20 tick 一次的 force 全量回放兜住丢包/分叉的客户端。这是一个经过验证的可靠模式。
- `FsmSyncState` 明确区分「服务端 version」与「客户端本地 version」（本地 tick 会污染后者）—— staleness 判断不被本地 tick 干扰，注释把理由写清了。
- `bind()` 清版本记录（`FsmSyncClient.java:55-58`），服务端机器重建 version 回卷也不会被永久拒绝。
- 线格式用 index 而非字符串 id（紧凑），且 index 越界**跳过 + warn 而不是炸包**（`FsmSyncClient.toSnapshot`）；`FsmSyncDedup` 把 player 降为 UUID 使纯 JVM 可测。
- 快照看作者是真的想把「在飞的 cross-fade」同步过去（`StateMachineSnapshot` 带 transitionId + elapsed）——意图很好，但见问题 1。

### 缺陷与改进建议（按严重程度）

**1. 实际 bug：cross-fade 起始不 bump version，「同步在飞渐变」的机制基本不可达**

- `Layer.fire()` 里：instant 转换置 `activeChanged = true`，但 **cross-fade 分支只设 `activeTransition`，不设 `activeChanged`**（`Layer.java:290-306`）。
- 后果链：cross-fade 开始 → version 不变 → `FsmSyncDedup.shouldSend` 判定无需推送 → 客户端不知情；cross-fade 完成时 `completeTransition` 才 bump version，但此刻快照里 `activeTransition` 已是 null。于是事件驱动路径**永远同步不到在飞的渐变**——只有心跳（最长 1 秒后）恰好落在渐变中途才能撞上。客户端实际表现是硬切，`conformTransition` 这套精心设计的恢复机制形同虚设。
- 修复：`fire()` 的 else 分支加 `activeChanged = true`。

**2. `definitionVersion` 作为身份检查在实践中会误杀（落实第 3 块问题 5）**

- 它是 MachineRegistry 的**全局单调计数器**：任何一次资源重载（哪怕是无关文件）、任何一次脚本 `registerDefinition`（包括幂等重注册）都 bump。
- 关键场景：服务端脚本注册/重注册一个定义 → 服务端计数器 bump → 客户端计数器没动 → **所有** FSM 同步包因 mismatch 被丢弃，直到下一次两端同时重载。一个脚本动作瘫痪整个 FSM 同步，且只有 warn 日志可循。
- 另外 payload 里 `(int) definitionVersion.getAsLong()` 把 long 窄化成 int（`FsmSyncServer.java:126`）。
- 建议：身份检查改为**按 machineId 的定义内容 hash**（client/server 各自对所用 definition 算 hash，线格式带 hash）。检查的对象应该是「两端用同一结构建了这台机器」，而不是「两端的全局注册表历史完全一致」。

**3. vars 不同步——契约未声明的分叉（落实第 1 块问题 8）**

- 快照/线格式只含结构（state/transition/elapsed），不含 vars。服务端脚本 `set` 的 var 客户端永远没有。
- 如果定位是「逻辑只在服务端跑，客户端是姿势傀儡」，这没问题——但第 4 块问题 1 指出客户端机器**也在本地评估 guard**，而 guard 读的就是这些 vars。两个问题叠加：客户端用分叉的 vars 评估出服务端没有的转换，心跳时再被掰回。
- 这进一步说明需要先做「客户端角色」的顶层决策：傀儡模式（客户端不评估 transition，vars 不同步也自洽）或预测模式（则 vars 必须进同步通道，`StateVar.streamCodec` 那个 reserved 字段就是为此留的）。

**4. 小异味**

- `FsmSyncDedup.sentVersions` 的清理覆盖 unbind/removePlayer/clearAll 三条路，但「玩家停止追踪某区块」（未登出）没有清理钩子——条目留到登出。量小，可接受，值得注释一句。
- `FsmSyncServer.push` 每次调用都遍历 targets 并懒建 payload——合理；但 `pushToChunkTrackers` 每 tick 新建 `ArrayList` 收集玩家（`FsmSyncServer.java:87`），大量 FSM 方块时是稳定的小分配流。
- 心跳计数 `tickCounters` 按 key 永久累加（`Integer::sum`），长跑服务器上 int 溢出在理论射程内（20 tick × 2^31 ≈ 6.8 年，可忽略，提一句而已）。
- `FsmSyncServer` 是 `public class` 且 `sendTo` 为 protected 可覆写（测试钩子），而 `FsmSyncClient` 是 final——测试可注入性的风格不统一，无妨但可对齐。

### 小结

同步块的**协议骨架是健康的**（去重、心跳、staleness、bind 重置都想到了），但有一个实打实的 bug（问题 1：cross-fade 起始不触发推送）和一个设计性误杀（问题 2：全局 definitionVersion 身份检查）。这两个修掉之前，「客户端平滑跟随服务端动画」的承诺是不成立的。问题 3 则是和第 4 块问题 1 互为因果的顶层决策点。

---

## 第 7 块：渲染绑定（`PoseSink` / `ModelInstancePoseSink` / `KasugaModelPipelines` / `MaterialResolver` / `TransformLerp`）

### 做得好的地方

- **`PoseSink` 是干净的单一接缝**：9 行的函数式接口，把 FSM 与 MC 动画类型（morph/skeleton/material）彻底隔开，服务端 sink=null 即逻辑机——这个抽象是整套绑定里最值钱的一个。
- `ModelInstancePoseSink` 忠实执行 Blender 的契约：每通道恰好一次写入，`ApplyMode` 三分派（replace/multiply/offset）清晰。
- `KasugaModelPipelines` 服务端安全（pipeline 未初始化即 no-op）、懒发布 + 下 tick 重试、实例被外部 detach 后能 remount 自愈。
- `MaterialResolver` 可插拔，默认解析器覆盖整数索引材质。

### 缺陷与改进建议（按严重程度）

**1. 实际缺陷：消失通道不复位，旧变换/morph 永久残留**

- 已核实底层语义：`SkeletonInstance.transforms` 是**持久** map（`SkeletonInstance.java:81,197-214` 提供显式 `reset/resetAll`，说明默认不清）；`MorphInstance` 同理（`deactivateMorph` 存在但 sink 从不调用，`MorphInstance.java:172`）。
- 而 `ModelInstancePoseSink.apply` 只写**当前 Blender 里存在的通道**（`ModelInstancePoseSink.java:37-59`）。于是：状态 A 的姿势动了骨骼 X / morph M，切到姿势不含 X/M 的状态 B 后——没有任何人把 X/M 写回中性值，**A 的最后一帧姿势永远留在模型上**。
- 这是状态机动画最经典的 bug 形态。可选方案：
  - sink 记录上一帧写过的通道集合，本帧消失的通道显式 `reset(bone)` / `deactivateMorph(id)`（增量、最省）；
  - 或文档化「每个状态必须为它想保持中性的通道显式给值」——把负担转嫁给数据作者，不推荐；
  - 或约定 BASE 层必须全覆盖（无法强制，且 ADDITIVE/OVERRIDE 层无此语义）。
- 推荐第一种，成本集中在 sink 一个类里。

**2. 不可路由模型的 warn 每 tick 刷一次**

- `route()` 对未知扩展名直接 `LOGGER.warn`（`KasugaModelPipelines.java:118`），而 `ensureClientModel` 每 tick 调 `createAndBind` → 每 tick 走 `route`。一个 typo 的 model 路径 = 客户端日志每秒 20 条 warn。同理 MMD 缺 `model_name` 的 warn（`:133`）。
- 建议：warn-once（按 modelLoc 去重），与项目里 `definitionWarned`/`warnedUnknown` 的既有模式对齐。

**3. `TransformLerp` 在热路径上批量分配**

- 每次调用 new 出 6+ 个 JOML 对象（`TransformLerp.java:29-31`），cross-fade 期间每骨骼每 tick 一次。与第 1 块问题 4 同属一个性能主题，建议一并处理（调用方传入 scratch 对象，JOML 的 API 本来就支持 dest 模式）。

**4. 小异味**

- `MaterialResolver.forInstance` 的字符串路径用 `Integer.parseInt` + try/catch 做控制流（`MaterialResolver.java:26-29`）——能工作，但「命名材质」完全依赖宿主自定义 resolver，JSON 数据作者若写了材质名会得到静默 null（frame 不生效，无日志）。建议解析失败时 debug 级日志。
- `KasugaModelPipelines` 用文件扩展名字符串匹配路由 pipeline——务实但脆：`.geo.json` 先于 `.json` 判断这个顺序依赖是隐式的，加一行注释或提取成有序表更稳。
- `Blender` 的累加器字段全 public（`MorphAccum`/`BoneAccum`/`FrameAccum`），sink 直接读写内部字段——接缝另一侧的封装是漏的。可接受（同包协作），但 `PoseSink` 作为公开接缝意味着第三方 sink 实现者也要懂这套内部结构，javadoc 里应给一份「如何实现自定义 sink」的最小说明。
- `instanceLoc` 的唯一性靠调用方自觉（javadoc「must be unique per host」）。`AnimationBlockEntity` 用坐标构造是对的，但没有机制防止两个宿主传同一个 instanceLoc 互相抢实例。

### 小结

渲染绑定的接缝设计（`PoseSink`）是**整套 FSM 里抽象最正确的地方**，问题 1 的残留通道是唯一的功能性缺陷——它不在接缝本身，而在「增量写入 vs 持久底层」的语义错配：FSM 以为自己在描述「当前姿势」，底层却在累积「历史写入」。修这个比在数据层打补丁（要求每个状态全覆盖）更符合现有架构。

---

## 第 8 块：横切问题（贯穿各块的设计层面）

### 1. `GLOBAL` 单例群：文档契约与真实接线不一致

- 全局单例有六个：`MachineRegistry.GLOBAL`、`StateVarRegistry.GLOBAL`、`FsmFunctionLibrary.GLOBAL`、`FsmSyncServer.GLOBAL`、`FsmSyncClient.INSTANCE`、`AnimatorApi` 默认构造里的同一批。
- `MachineRegistry` 的 javadoc 说「hosts should own a dedicated MachineRegistry instance so same-id machines never shadow each other」——但**实际接线是反的**：`FsmMachineBuilder.findDefinition` 硬编码 `GLOBAL`（`FsmMachineBuilder.java:53`），`StateMachineDefinitionLoader` 默认注入 `GLOBAL`，方块宿主的定义恰恰全从 GLOBAL 读。文档契约（宿主自有 registry）与真实架构（定义桶全局共享、实例桶才算宿主私有）自相矛盾。
- 风险不在当下（单机集成端两边各跑各的 JVM 内副本，撞不了），而在契约误导：按 javadoc 行事的宿主会建一个私有 registry，然后发现 loader / sync / 脚本全都不看它。
- 建议：把契约改写成真实架构并讲清理由（定义桶全局共享是有意的：loader/sync/脚本三方需要单一事实源；实例桶按宿主隔离），或反过来真正注入。两者取一，不能维持现状。

### 2. 菜单 / GUI 绑定完全缺失

- 全库没有任何 FSM × menu/screen 的接线：`FsmBlock` 无 menu provider，无 screen 引用机器。
- 如果定位是「动画状态机」，这是合理留白；但 vars + trigger 机制已经是通用状态机雏形，GUI 驱动（按钮 → trigger → 状态切换）是最自然的下一个绑定面。建议在路线图上显式声明：要么「FSM 只服务动画，GUI 不在范围」，要么预留绑定设计（`MachineQuery.machineAt` 已经是最小够用入口）。

### 3. 脚本定义永久压制资源定义，无撤销路径

- `registerDefinition`（SCRIPT）覆盖 RESOURCE 且 `registerResourceDefinition` 不反压（`MachineRegistry.java:70-90`）——「script wins」是双向锁死的：脚本注册一次之后，**/reload 永远恢复不了数据包版本**，除非 `clearDefinitions()`（核弹，连无关脚本定义一起清）。
- 叠加第 6 块问题 2（脚本注册 bump 全局 definitionVersion → 全部同步断流），「脚本覆盖资源定义」这个动作的代价被放大了。
- 建议：提供 `removeDefinition(id)`（按 id 驱逐 SCRIPT 条目，RESOURCE 桶重载后可复活），并把 definitionVersion 换成内容 hash（第 6 块问题 2 的修复同时缓解这里）。

### 4. 概念冗余：`Blackboard` / `multiplexer` 与 FSM 的平行宇宙

- `Blackboard`（typed/raw 双键空间）已被 FSM 弃用，javadoc 自己声明「FSM no longer uses it」，但类还留在 `dynamic/data/` 服务 multiplexer 子系统。
- `multiplexer/`（`Multiplexer`/`MuxState`/`Variant`/`SelectorPredicates`）与 FSM 在概念上明显重叠：都是「按条件选择姿态变体」。两个子系统并存意味着用户要问「我该用哪个」——目前没有文档回答这个问题。
- 建议：写一段对比文档（multiplexer = 无时间维度的静态变体选择；FSM = 有时间/转换/混合的动态图），或长期把 multiplexer 表达为 FSM 的退化形态（单状态 + 条件选择）收编之。至少 `Blackboard` 应搬出 `dynamic/data/` 或改名，避免新用户误以为它是 FSM 的数据面。

### 5. 线程模型整体缺一份顶层声明

- 各处的线程约定散落在 javadoc 角落：sync apply 在 main thread（`FsmSyncChannel` enqueueWork）、脚本机器单线程自 tick、BE 机器在各自端 tick 线程、`MutableStateMap` 是裸 HashMap、`MachineRegistry` 用 ConcurrentHashMap。
- 拼起来是对的，但没有任何一处把整张图画出来。对一个要被脚本、网络、渲染三方同时摸的系统，值得一篇 `doc/fsm-threading.md`（或并进主文档一节）：谁能从哪个线程碰什么。

### 6. 注册时机的「类加载 forcing」模式

- `FsmSyncChannelRegistrar` 用 `@PostConstruct` 里 `PAYLOAD.toString()` 强制类加载（`FsmSyncChannelRegistrar.java:16`）——能用但脆弱且隐晦：静态注册 + 注册窗口 + 类加载顺序三者耦合，后来者极易在重构时意外打破（比如把 PAYLOAD 挪到别的类）。
- 建议：至少把这个模式收敛为一个显式工具方法（`RegistryBootstrap.forceLoad(Class...)`），让「这里在强制类加载」成为一眼可见的意图。

---

## 总结

### 总体判断

这套 FSM 的**架构选型整体是优秀的**：泛型 owner + scoped context 的引擎核心、DataComponent 式类型化变量、定义/实例解耦的重载语义、`PoseSink` 单一渲染接缝、事件驱动 + 心跳的同步骨架——每一块的基础决策都站得住。测试覆盖（16 个单测 + 游戏测试 + JMH）也超出同类库的平均水平。

问题集中在三个层面：

1. **契约严密性**：构建期零校验（块 1.1）、id-only 相等性破坏类型安全承诺（块 2.1）、文档契约与真实接线不一致（块 8.1）。
2. **两个实际 bug**：cross-fade 起始不 bump version 导致渐变同步机制不可达（块 6.1）；消失通道不复位导致姿势残留（块 7.1）。
3. **未做的顶层决策**：客户端机器的角色（傀儡 vs 预测，块 4.1 + 块 6.3）、vars 是否进同步通道、脚本实例的多实例语义（块 5.1）、运行时状态是否持久化（块 4.2）。

### 建议的修复优先级

| 优先级 | 项 | 出处 | 理由 |
|---|---|---|---|
| P0 | cross-fade 起始设 `activeChanged = true` | 块 6.1 | 一行修复，让已建好的同步机制真正生效 |
| P0 | 消失通道复位（sink 记录并 reset/deactivate） | 块 7.1 | 用户立即可见的功能缺陷 |
| P1 | 构建期校验（重复 id、跨层引用、结构引用 warn） | 块 1.1 + 块 3.2 | 把一类静默错误变成启动期报错 |
| P1 | definitionVersion → 按定义内容 hash | 块 6.2 + 块 8.3 | 消除脚本注册瘫痪全量同步的场景 |
| P1 | 决策并文档化「客户端机器角色」 | 块 4.1 + 块 6.3 | 多个 bug 的共同根源，不修则抖动永存 |
| P2 | `MutableStateMap.set` 类型冲突 fail-fast | 块 2.1 | 把 CCE 从调用点提前到肇事点 |
| P2 | 工厂泛型化 `<O>`（消灭 `StateMachine<Object>`） | 块 3.1 | 纯泛型传播，恢复数据驱动端的类型安全 |
| P2 | `StateVar.trigger()` 专用工厂 + trigger 锁存选项 | 块 2.3 + 块 1.3 | 消除时序脚枪 |
| P3 | 脚本绑定三件：多实例语义、错误哲学统一、注册兼容性检查 | 块 5.1/5.2/5.3 | 脚本作者的静默故障 |
| P3 | inline var 生命周期并入定义加载/卸载 | 块 3.3 | 消除泄漏与重复注册 |
| P3 | 持久化三字段 + 运行时状态持久化决策 | 块 4.2 | 程序化 API 的完整性 |
| P4 | 性能：Blender 复用、blend/lerp 去分配 | 块 1.4 + 块 7.3 | 有 JMH 基线，可量化验证 |
| P4 | 日志降级（INFO→DEBUG）、warn-once 对齐 | 块 4.4 + 块 7.2 | 生产环境日志卫生 |
| P4 | 文档：线程模型图、multiplexer vs FSM、GUI 定位 | 块 8.2/8.4/8.5 | 降低下一个维护者的入门成本 |

---

# 第二轮复查（2026-08，大部分第一轮建议实施后）

第一轮报告落地后，相关改动约 2100 行（92 个文件，未提交）。本轮逐条核对修复情况，并审查新增/大改代码。结论先行：**第一轮的两个 P0、三个顶层决策、大部分 P1/P2 已确实修复且测试闭环；但本轮在两条「端到端接线」上发现第一轮未覆盖的致命盲区——脚本控制面从 JS 不可达、数据驱动方块的生产链路断裂。**

## 一、第一轮问题核对总览

### 第 1 块（核心引擎）
- 已修：混合时钟文档化（`Layer.java:254-261`）、trigger 锁存（`StateMachine.java:219-235` + `Transition.onBuffered`，`TriggerLatchTest` 覆盖）、Blender 单例复用 + reset、`unlockLayer`/`isLayerLocked`、构建校验三项 fail-fast（重复 layer/state id、跨层 transition 引用，`BuildValidationTest` 覆盖）。
- 部分修：构建校验清单**没收口**——`Layer.initial()` 无成员校验（别层 state 设为 initial → 该层转换全部静默失效，`Layer.java:72-75`）、transition id 不查重（影响 `conformTransition` 按 id 恢复）、孤儿状态仍无校验；每 tick 分配只剩半（cross-fade 路径 `Layer.blend` 仍 3 个 HashSet + Pose.Builder，`activePose()` 每 tick 重建 Pose）；可变 `layers()`、goTo 单槽、字符串 lockLayer 维持「文档化让步」。

### 第 2 块（类型化变量存储）
- 已修：`MutableStateMap.set` 类型冲突 fail-fast + get 双保险、Vector3f 默认值防御拷贝、`StateVar.trigger()` 专用工厂、`declaredVars()`、`compatible()` 纳入 ephemeral、线程契约（`doc/fsm-threading.md`）。
- 残留：`declaredVars()` 只对数据驱动构建填充，纯 DSL 机器恒空（javadoc 未写明）；手写 builder 的「自杀写法」（ephemeral+default true）仍无拦截。

### 第 3 块（数据驱动定义）
- 已修：initial_state/from/to 聚合告警、非 ephemeral trigger warn、inline var 清理（`clearForMachine` 挂进三条清理路径）、**definitionVersion → per-id 内容 hash**（`MachineRegistry.definitionHash`，线格式同步改为 int，窄化问题随之消失）、script-wins 双向 + `removeDefinition` 撤销路径、工厂泛型化（`DefinitionStateMachineFactory.build(O) → StateMachine<O>`）。
- 部分修：**泛型在 `FsmMachineBuilder` 门口断掉**——宿主主入口仍是 `StateMachine<Object> build(Object owner, ...)`（`FsmMachineBuilder.java:33,39`），`AnimationBlockEntity` 拿到的还是 `StateMachine<Object>`，纯签名改动没做。
- 未修：StateVarDefinition reference+default 矛盾输入不校验、「sink 非 null ⇒ clientSide」隐式契约、loader 的 `javax.annotation.Nullable`、schema 版本字段。

### 第 4 块（方块绑定）
- 已修（质量高）：**客户端傀儡模式真接了线**——`setLogicEnabled(false)` + `advancePuppet` + 文档三者一致（`AnimationBlockEntity.java:198-204`、`StateMachine.java:151-158`），不是只加开关没人用；NBT 持久化三字段 + 运行时状态不持久化的显式决策；`newBlockEntity` 扫描缓存；sync key 换 id 前 unbind；BE 内 INFO→DEBUG；`machine` 字段 volatile。
- 未修：`MachineQuery` 双路径不一致、`SYNC_SERVER` 静态别名、`KasugaModelPipelines` 的 bind/unbind INFO。

### 第 5 块（脚本绑定）
- 已修：旧 handle 失效改为显式 warn + listener 通知、`registerStateVar` 短路前兼容性检查、`varType(varId)` 去死参、`warnedUnknown` 改 LRU 上限 256、定步长 `tick(handle)` 重载、「脚本机器无渲染/同步通道」写入 `doc/fsm-threading.md`。
- 部分修：错误哲学走「文档化两段式」（builder 抛、control 不抛），但 `registerDefinition` 抛的是 Gson `JsonSyntaxException` 而非文档宣称的 `IllegalArgumentException`；多实例语义维持 1:1 替换；`coerceVec3` 仍归零（只加了未节流的 warn）；`declaredVars()` 未暴露给脚本；`tick(handle, dt)` 边界 javadoc 仍无时钟提示。

### 第 6 块（网络同步）
- 已修（测试闭环）：cross-fade 起始 bump version 后事件驱动同步真正可达（`StateMachineSnapshotTest`、`FsmSyncPayloadCodecTest`）；内容 hash 身份检查 + 线格式升级（wire version "2"）；傀儡模式消除 vars 分叉；`FsmSyncClient` 可注入构造对齐。
- 未修（均为第一轮自评「可接受」项）：停止追踪区块的玩家无 dedup 清理钩子（连建议的注释都没加）、`pushToChunkTrackers` 每 tick ArrayList、tickCounters 溢出。

### 第 7 块（渲染绑定）
- 已修：**消失通道复位**（`ModelInstancePoseSink` 记录上一帧通道，消失的 morph `deactivateMorph`、骨骼 `reset(name)`；`StateMachine.tick` 配合改为有 sink 即 flush；frame 不复位是有意决策并写入 javadoc）；route() warn-once。
- 部分修：`TransformLerp` 改 dest 模式但内部每次调用仍 new 约 9 个 JOML 对象（`TransformLerp.java:29-31,39-41`）；Blender 累加器仍全 public（补了 javadoc）；扩展名路由顺序仍隐式。
- 未修：MaterialResolver 命名材质静默 null、instanceLoc 唯一性靠自觉。

### 第 8 块（横切）
- 已修：GLOBAL 契约改写为真实架构（定义桶全局共享是有意的）、`removeDefinition`、`doc/fsm-threading.md`（逐条与代码核对一致）。
- 部分修：Blackboard javadoc 澄清（未搬位置、multiplexer 对比文档未写）；类加载 forcing 加了注释但未收敛为显式工具。
- 未修：GUI 绑定定位仍无声明。

## 二、新发现问题（按严重程度）

### 致命（端到端链路断裂，现有测试恰好全部绕开）

**N1. `AnimatorApi` 控制面整体从 JS 不可达（long 参数桥接盲区）。**
所有控制方法首参是 `long handle`，而 Javet 桥接只认 `Long↔long` 自动装箱，JS number 到 Java 是 `Integer`/`Double`——反射找不到方法。`FsmApiAssemblyTest.java:33-36` 的 javadoc 自己承认这一点，端到端测试只能靠 Java 侧代打 tick/read。后果：脚本能 `registerDefinition`/`instantiate` 拿到 handle，但 `tick/get/set/trigger/goTo` **一个都调不动**——本轮 +280 行的控制 API 在真实脚本里全部落空。修法：handle 改 String/double，或桥接层加数值 widening。附带：`registerCondition/registerAction` 的函数式接口入参大概率同样不可达（JS function 不会被适配成 `Predicate<StateContext<?>>`，无测试覆盖此路径），JSON 里的 `when`/`on_enter` 解析不到脚本函数时 factory 静默降级 condition→false，状态机永不转换。

**N2. 数据驱动 FSM 方块的生产链路断在两处。**
- `fsm_block`/`fsm_be` 工厂**只在测试源集注册**：`FsmBlockEntityFactories` 自觉不做 @Context bean（javadoc 自述），全库唯一触发点是 `DataDrivenTestFactories.java:38`——生产运行时 `type: "fsm_block"` 只会得到 "Unknown block entity type" warn。且 `doc/data-driven-registration.md` 新增段落称它「@Context 静态注册」，与该类自述直接矛盾。
- 非 MMD 模型不配 `model_name` 时**客户端永不绑定且完全静默**：`ensureClientModel` 入口要求 `modelName != null`（`AnimationBlockEntity.java:325`），但 `modelName` 只有 MMD 管线需要。官方示例 `fsm_blocks.json:11` 恰恰只配了 `"model"`（OBJ）没配 `model_name`——示例自身的客户端渲染就不工作，程序化 fixture 传了 `"cube"` 所以测不出来。门槛应只在 MMD 路由处检查。

### 中（真实场景下会咬人）

**N3. hash 身份校验与运行中机器的构建结构脱钩。** 服务端机器构建后被缓存，reload 不重建（`InvalidationListener` 仍 reserved 未接线），而 `FsmSyncServer.toPayload` 每次推送现取注册表 hash（`FsmSyncServer.java:127`）。若 reload 改了定义且只有一侧重建机器，两端 hash 都是新定义的——校验通过，但服务端发旧结构下标、客户端按新结构解析，**下标在界内却指向错误状态，静默错播**。修法：机器构建时捕获 definitionHash 存进实例，payload 带构建时 hash。

**N4. `clearForMachine` 前缀匹配误删其他机器的 inline var。** 清理条件是 path 以 `machineId.getPath() + "/"` 开头（`StateVarRegistry.java:82-89`）：机器 `test:a` 与 `test:a/b`（数据文件可放子目录）场景下，清 `test:a` 会误删 `test:a/b` 的 var。修法：注册时记录 var→machine 归属，或精确匹配段。

**N5. inline var 注册时机错位 + `byToken` 抛异常破坏降级哲学。** var 在 build 期注册、reload 期清除，两者之间存在窗口：/reload 后脚本按 id 解析全部落空，直到某宿主重新 build。且 `StateVarType.byToken` 对未知 token 直接抛异常（`DefinitionStateMachineFactory.java:116`）——全厂其他错误都是降级+warn，唯独 var 类型 typo 会把异常抛穿 `build()`：一个 var 拼写错误 = 整台机器 null（`FsmMachineBuilder.java:38-44`），脚本路径还会抛穿边界（与 5.2 叠加）。建议 `byToken` 失败也走 warn + 跳过该 var。

**N6. `Transition.fires()` 在求值中消费 latch，谓词带副作用。** `Transition.java:137-140`。当前唯一调用点 evaluate-then-fire 紧耦合所以安全，但任何未来的推测性求值、或 fire 动作抛异常，都会让 latch 已消费而转换未落地。latch 消费应挪到 `fire()` 成功路径里。

**N7. 程序化赋值不 `setChanged()`，持久化可能写不进盘。** `stateMachine(id)`/`model(loc,name)`（`AnimationBlockEntity.java:82-109`）改字段后未标脏，常驻区块 + 自动保存窗口内退出/崩溃时改动丢失。另：`loadAdditional` 不使已建机器失效（覆盖三字段后旧机器/旧 sync key 继续存活，与 4.6 刚修好的泄漏同族）。

**N8. MCRenderableContext 的「root 已烘焙」前提无端到端测试兜底。** 新实现改为 TRS 提取 + `appliesTransform=false`，前提是 root 已烘焙进所有骨骼 absoluteTransforms——但最终顶点是否吃到它取决于各 bridge 的 `getBoneBindingFunc`（`SkeletonInstance.java:331-355`）。若某 pipeline（OBJ/JE 刚性绑定）不用 absTransform，root 平移整体丢失、模型渲染在原点。现有测试只测提取函数本身。另：TRS 提取对负缩放/剪切是有损近似且静默，欧拉约定测试只覆盖一组角度、缺万向锁附近分支。

**N9. 脚本机器没有销毁通道，`MachineRegistry.GLOBAL` 只进不出。** `invalidate/remove` 存在但 `AnimatorApi`/`AnimatorBuilderApi` 均未暴露 dispose——每次 `instantiate` 永久驻留四张 map，长跑服务器确定性泄漏。

### 低（值得记一笔，不紧急）

- `clearAll` 清理路径是空头支票：`FsmSyncServer.clearAll`/`FsmSyncClient.clearAll` 的 javadoc 声称 reload 时调用，生产代码无任何调用方。
- `hashOf` 的 0 值歧义 fail-open：定义不存在与 encode 失败都返回 0，「服务端 encode 失败 + 客户端未加载」组合下校验静默通过（`MachineRegistry.java:164-178`）。建议哨兵 -1 或 hash=0 一律拒绝。
- hash mismatch warn 每心跳刷一次无去重（`FsmSyncClient.java:91-94`）；`asFloat` warn 未节流（`StateValueCoercer.java:105`）。
- 心跳 force 包把客户端在飞 transition 的 elapsed 回退约一个单向延迟（覆盖式 conform），渐变途中每秒一次小幅视觉回退，值得文档写一句。
- `FsmSyncClient.bind` 不要求 `setLogicEnabled(false)`，第三方宿主 bind 一台 logicEnabled 的机器即复活 vars 分叉——bind javadoc 应写明或强制关闭。
- puppet 机器上 `goTo` 静默失效且 `pendingGoTo` 滞留（日后重新开逻辑会突然生效），一行守卫即可。
- `StateContext.goTo` 不做 layer 空检，与同 record 其他方法不一致（`StateContext.java:143-145`）。
- `coerceVec3` 的 List/Map 路径在真实 JS 下疑似死代码（JS 数组/对象经 Javet 后是 V8Value/bridge，不是 List/Map），vec3 var 可能永远 set 不进去——因 N1 存在无法有 JS 测试证伪。
- `buildVar` 对无法 coerce 的 defaultValue 静默回退 zeroDefault 无 warn（`AnimatorBuilderApi.java:133-140`）。
- `InvalidationListener` 通知不一致：`removeDefinition` 通知，`registerDefinition` 覆盖与两个 clear 不通知；`definitionVersion` javadoc 过期（sync 已不再消费它）而测试还在断言它前进，把过时语义锁进了测试。
- loader 不检测跨文件重复 id（两 JSON 同 id 静默覆盖）；`validateDefinition` 不校验 `state_vars` 内部（同名 var/同层重复 state id 静默覆盖）。
- `Blender.resolveBoneWrites` 每骨骼每 tick new ArrayList + BoneWrite record——与去分配方向相反。
- `Constants.testModel` 硬编码测试模型默认开启（应默认关或仅 dev）。
- `FsmBlock` 静态单槽缓存：两个不同 FSM BE 类型时未占槽的那个每次全量扫描 + probe 构造。
- `FsmMachineBuilderTest` 向 GLOBAL 注册后无清理，进程内全局态泄漏。
- `BlockEntityTypeHandler.extractEmbedded` 对缺 `id` 的块 JSON 会 NPE。
- 新测试 `MCRenderableTransformExtractTest` 类 javadoc 用中文，与项目英文注释惯例不一致。
- `FsmPilots.ACTIVATE` 手写 builder 而不用自家 `StateVar.trigger()` 工厂，旗舰示例没带货。
- `doc/fsm-threading.md` 未提 `StateMachineDefinitionLoader` 在 reload 哪个线程注册进 GLOBAL（新人最易踩的坑）。

## 三、本轮总结

第一轮的修复执行质量整体很高——凡是修了的项基本都有对应新测试锁住关键属性（构建校验、trigger latch、内容 hash、通道复位、傀儡模式），文档（`fsm-threading.md`、GLOBAL 契约）与代码实际吻合，没有「修了 surface 留了根」的情况（傀儡模式、hash 线格式都是真接线）。

但本轮暴露的两条致命项有一个共同形态：**单元层都对了，端到端接线断了，而现有测试恰好全部绕开断点**——脚本 API 的测试用 Java 代打绕开了 Javet 桥接的 long 盲区；方块链路的测试在测试源集注册了生产环境没人注册的工厂、又用了程序化 fixture 绕开了 `model_name` 门槛。这类问题只有「真 JS 跑一遍」「真生产环境加载一次数据驱动 JSON」才能发现。

### 建议的下一步优先级

| 优先级 | 项 | 出处 | 理由 |
|---|---|---|---|
| P0 | 脚本控制面可达性：handle 改 String/double 或桥接加 widening；补真 JS 端到端测试 | N1 | 整套脚本 FSM API 目前对脚本作者不可用 |
| P0 | 数据驱动方块生产链路：工厂生产注册 + `model_name` 门槛挪到 MMD 路由 | N2 | 官方示例在真实环境「类型不识别 / 没有画面」 |
| P1 | 机器构建时固化 definitionHash | N3 | reload 场景静默错播，与刚修好的 hash 机制同源 |
| P1 | `clearForMachine` 误删 + `byToken` 降级 | N4/N5 | 跨机器误伤与「一个 typo 炸整台机器」 |
| P2 | latch 消费挪进 `fire()`；`setChanged()` + `loadAdditional` 失效机器 | N6/N7 | 定时炸弹与持久化收尾，都是几行的事 |
| P2 | 脚本 dispose 通道 | N9 | 长跑服务器确定性泄漏 |
| P3 | MCRenderableContext 渲染冒烟测试（contentTesting） | N8 | 挡住 root 烘焙前提的回归 |
| P3 | 泛型最后一步（`FsmMachineBuilder<O>`）、构建校验收口（initial 成员性、transition id 查重） | 块 3/块 1 残留 | 第一轮修了一半的项 |
| P4 | 低优先级清单（warn 节流、文档补笔、测试卫生） | 上文低优项 | 择机顺手修 |
---

# 第三轮专项：脚本交互体验审查（script 是主场景）

前两轮审的是正确性，本轮换一个视角：**一个脚本作者从放文件到调出动画的完整旅程是否简洁方便**。材料：`AnimatorApi`/`AnimatorBuilderApi`/`StateValueCoercer`/`FsmApiRegistration` 精读 + 脚本基础设施全链路调研（SLP、包发现、Javet 桥接）+ 真 V8 测试运行（JDK 21，本机 macOS）。

**实测事实**：`./gradlew :external:slp:javet:test --tests FsmApiAssemblyTest` —— 5 个测试 4 过 1 红。过的 4 个证明核心回路（registerDefinition → instantiate → trigger/tick/set/get，全真 JS 驱动）已经打通；红的那个是 `shouldRegisterConditionAndActionFromJsAndEvaluateWithOwner`，失败于 `ClassAccessor.invoke:197` 的 "Illegal invocation"——**JS 箭头函数注册守卫/动作这条路实测不通**。

## 一、 API 骨架评价：简洁性合格

- **双全局分工清晰**：`AnimatorBuilder`（注册定义/var/行为、实例化）与 `Animator`（运行时控制）分离，符合「先建后控」的心智模型。
- **窄命名方法，无字符串路径语言**：`getState(h, "main")`、`getLayerMode`、`isLayerLocked` 都是具体方法而非通用 query 语言——学习面小，补全友好。
- **int handle 是对的选择**：修复后 handle 在 JS 里是普通 number，可 round-trip（测试 `shouldDriveMachineAndVarsEntirelyFromJs` 锁住）。
- **coercion 宽容**：JS number 自动适配 int/float，vec3 收 `[x,y,z]` 数组，resource 收字符串——`StateValueCoercer` 的 widen 规则符合作者直觉。
- **两段式错误哲学已文档化**：builder 抛（JS 可 catch 且引擎不死，有测试）、control no-op + warn-once（LRU 有界）。
- **数据/脚本混合工作流是通的**：脚本可直接 `instantiate` 数据包 `state_machines/*.json` 里的定义（RESOURCE/SCRIPT 统一注册表）——这其实是主场景最自然的用法：美术改 JSON，脚本只写逻辑。

骨架没问题。问题全在「主场景最关键的另外三件事」上。

## 二、关键缺口（按对主场景的伤害排序）

### 1. JS 写不了行为逻辑——脚本只能搭骨架（实测确认，P0）

脚本作为**主要场景**，其存在的核心价值是「在 JS 里写守卫和动作」。但 `registerCondition(String, String, Predicate)` / `registerAction(String, String, Consumer)`（`AnimatorBuilderApi.java:69-78`）的函数式接口入参，桥接不支持：JS function 到 Java 是 `JavetEntityFunction`，匹配不到 `Predicate`/`Consumer` → "Illegal invocation"。

失败形态是双重静默：注册时拿到笼统的 "Illegal invocation"（JS 侧没有「哪个参数不匹配」的信息，详细信息只在 Java 日志）；就算作者绕过注册，JSON 里引用的 `when`/`on_fire` 解析不到时 factory 静默降级 condition→false——状态机永不转换，没有任何有效报错指向原因。

**现成解法已在库内**：`TimerModule.setInterval(ScriptValue, int)` 用的 `ScriptValue`/`ScriptFunction` 通道在生产可用（`TimerModule.java:36-53`）；Java 对象→JS 代理、Java lambda→JS function 两个方向桥接也都支持。`registerCondition` 改收 `ScriptFunction`、把 `StateContext` 作为代理对象传给 JS 回调即可，`ctx.owner()` 模式（测试里已设计好）就能成立。这是把脚本主场景从「残废」拉回「可用」的唯一关键改动。

### 2. 没有 autoTick/onTick——驱动模式要作者自己拼 timer（P1）

脚本机器必须由脚本 `Animator.tick(h)` 驱动，但没有每 tick 回调 API。最近的工具是 `require("kasuga:timer").setInterval(fn, 50)`（ms/50 量化、最少 1 tick）。于是主场景的标准写法是：

```js
const timer = require("kasuga:timer").default;
const h = AnimatorBuilder.instantiate("test:js_ctrl", null);
timer.setInterval(() => Animator.tick(h), 50);  // 每个作者都要自己发现这个模式
```

这个模式没有任何示例展示（唯一 shipped 示例是 `ScriptingTestApi.printText` hello world，且不是服务端安全写法）。建议：`instantiate` 增加 autoTick 选项（由 `ScriptingSystem.dispatchTick` 驱动，已有逐 tick 唤醒机制），或暴露 `Animator.onTick(cb)`；至少提供一个完整的 FSM 示例脚本包作为脚手架。

### 3. 桥接残留的类型地雷（P1）

- **`tick(h, dt)` 的 float 重载 JS 不可达**（JS Double 不匹配 float）→ "Illegal invocation"。有了定步长 `tick(h)` 之后这个重载价值不大（还鼓励变步长脚枪，见第一轮块 1.2），建议改 `double dt` 或直接删掉。
- **`getTick(h)` 返回 long → JS BigInt**（`AnimatorApi.java:194`）。BigInt 与 number 不能混合运算、`JSON.stringify` 遇 BigInt 直接抛——作者拿 tick 计数做任何算术都会踩。tick 计数超 2^53 无实际可能，改返回 `double`/`int`。
- **`registerDefinition` 必须手写 `JSON.stringify`**：参数是 `String`，直接传 JS 对象会 "Illegal invocation"。所有测试里都写着 `JSON.stringify({...})`——这是肌肉记忆税。收 `ScriptValue` 后在 Java 侧序列化即可让 `registerDefinition({...})` 自然成立。

### 4. 发现性与 id 人体工学（P2）

- 脚本操作 var 要写**全 id** `Animator.trigger(h, "test:js_ctrl/go")`——「machineId + "/" + 短名」的拼接规则只存在于 Java 侧实现里，定义 JSON 里用的是短名 `"go"`，两边不一致且无任何作者文档。写错只 warn 一次（warn-once 永久去重，reload 才重置）。
- `declaredVars()` 引擎侧已有（`StateMachine.java:194-196`）但 `AnimatorApi` 未暴露；`listVars` 只列显式设置的 var——脚本无法发现「这台机器有哪些 var、叫什么 id」。
- 建议：暴露 `declaredVars(handle)`；`set/get/trigger` 支持短名（handle 已知定义 id，自动补前缀），全 id 保留兼容。

### 5. 句柄生命周期与隔离（P2，承接前两轮）

- 无 dispose：`MachineRegistry.GLOBAL` 只进不出，每次 `instantiate` 永久驻留（第二轮 N9）。脚本侧加个 `Animator.dispose(h)` 即可。
- handle 无所有权：每包独立 V8 引擎的隔离被进程级 GLOBAL 注册表打穿——任何脚本包能 tick/set 任何 handle。主场景下如果定位是「可信的 pack 作者」，可接受，但应写入文档。

### 6. 工作流摩擦（P2，非 FSM 特有但直接砸在脚本作者头上）

- **生产 `require` 只支持 `kasuga:timer`**：多文件脚本（`require("./utils")`）在单元测试的手工接线里能跑，生产 `setupRequireResolver` 只查内建模块表，直接抛 "Module not found"。测试能跑生产不能跑，是最恶劣的误导形态。
- `entry.server/client` **不做物理侧过滤**：标了 `server` 的入口在客户端引擎里也会跑（`ScriptPackage.start:91-98`）。
- **脚本级错误只进日志**：语法错/运行错对玩家和服主完全不可见；客户端有引擎缺失的整屏错误页，脚本错误没有对应物。脚本包静默死亡。
- 热重载整体重建：JS 状态（句柄、闭包、定时器）全丢，脚本需在入口重建一切——对动画机可接受，但作者文档必须写明。

### 7. 小体验项（P3）

- vec3 只收 `[x,y,z]` 数组，`{x,y,z}` 对象不可达（到 Java 是 `JavetValueObject` 不是 Map）——应文档化或在桥接支持 ScriptObject 读属性。
- `registerStateVar` 不兼容时仍返回成功形状的 id；`buildVar` 对无法 coerce 的 defaultValue 静默回退 zeroDefault 无 warn。
- `AnimatorApi` 没有 `getVersion` 之外的变更订阅手段，脚本想「状态变化时做点什么」只能每 tick 轮询 `getState`——配合 autoTick 可接受，但一个 `onStateChanged` 回调会让主场景代码更直。
- `ScriptingTestApi.printText` 示例脚本在服务端环境会调客户端类，作为唯一示例误导性强。

## 三、总体判断

**「简洁」已经做到，「方便」还差三件。** API 骨架（双全局、窄方法、JSON 定义、int handle、宽容 coercion）是简洁的，核心回路已实测全通。但脚本作为主场景，当前体验是：能注册定义、能实例化、能拨变量、能手动 tick——**唯独不能在 JS 里写行为逻辑**（缺口 1），而且要自己拼 timer 驱动（缺口 2）、自己猜 var 的长 id（缺口 4）。这三件补齐之前，脚本 FSM 的实际定位只是「数据驱动定义的遥控器」，而不是「脚本驱动的状态机」。

### 修复优先级（脚本专项）

| 优先级 | 项 | 理由 |
|---|---|---|
| P0 | `registerCondition/registerAction` 改 `ScriptFunction` 通道 + 修好现有红测试 | 脚本写行为 = 主场景的核心价值；解法（TimerModule 模式）已在库内 |
| P1 | autoTick / `Animator.onTick` + FSM 示例脚本包 | 消灭每个作者重复发现的驱动模式 |
| P1 | 桥接类型地雷：`tick(h, double)`、`getTick` 去 BigInt、`registerDefinition` 收对象 | 都是作者必踩的 "Illegal invocation" |
| P2 | `declaredVars(handle)` 暴露 + 短名解析 + `dispose(handle)` | 发现性与生命周期 |
| P2 | 生产 `require` 支持包内相对模块（与测试接线对齐） | 测试能跑生产不能跑是误导 |
| P3 | 错误可见性（脚本错误页/命令反馈）、`onStateChanged`、文档（长 id 规则、热重载语义） | 体验收尾 |

附注：本轮为跑测试发现构建需 JDK 21（JDK 25 下 `:gradle-plugin:compileGroovy` 失败，"Unsupported class file major version 69"）——原 AGENTS.md 记录过该要求但被删（commit e28f6b7），建议在 README 或新的 AGENTS.md 里补回。
