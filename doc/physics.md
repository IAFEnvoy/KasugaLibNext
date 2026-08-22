# Physics、IK 与 Pose Effectors API

Kasuga modelling 的原生物理由官方 Box3D C17 引擎负责。Java 层只处理对象映射、固定步长、Minecraft
碰撞数据、动画目标与骨骼写回；没有 Java 备用积分器或接触求解器。

当前 vendored 版本固定为 Box3D 官方 `main` 提交
`30c67b5e6d0a3a66f0f506c69ce9e9e0587e3b7c`。源码与许可证分别位于
`modules/modelling/src/main/native/box3d` 和 `modules/modelling/THIRD_PARTY_NOTICES.md`。

## 1. 每帧求值顺序

带物理的模型采用固定顺序：

```text
PoseDriver.sample(partialTick)
  -> PoseEffector.BEFORE_IK
  -> PMX IK
  -> PoseEffector.AFTER_IK
  -> Box3D fixed step(s)
  -> physics bone writeback
  -> PoseEffector.AFTER_PHYSICS
  -> renderer upload
```

`MinecraftRagdollRuntime` 在 `RenderFrame.Pre` 中先采样动画再推进物理，backend 随后的 `sample` 会消费
本帧已准备标记，不会用动画重新覆盖物理结果。每个 fixed step 都会重新执行 Box3D 前的 controller，
因此连续 force 不会在一次 catch-up 中只影响第一个子步。

手动模式可以调用：

```java
instance.animate(gameTickSeconds);
instance.sample(partialTick);
instance.simulatePhysics(frameSeconds);
```

需要一次完成动画采样和物理推进时使用：

```java
instance.evaluatePhysicsFrame(partialTick, frameSeconds);
```

## 2. 通用刚体 API

主要包：

```text
lib.kasuga.rendering.models.uml.dynamic.physics.core
```

### 2.1 Shape 与 body

`BodyShape` 是 body-local 的 sealed API：

- `BodyShape.Sphere(center, radius)`
- `BodyShape.Box(center, rotation, halfExtents)`
- `BodyShape.Capsule(centerA, centerB, radius)`

`SimBody.collisionShapes()` 可返回任意数量的 child shape，因此一个 native body 可以是 compound body。
兼容字段 `shape()` 与 `shapeSizeRef()` 仍会自动映射成一个居中 primitive。

`GenericRigidBody` 是非 PMX 内容的默认实现：

```java
GenericRigidBody sphere = GenericRigidBody.sphere(0.3f, 2f)
        .at(0f, 4f, 0f)
        .friction(0.7f)
        .restitution(0.1f)
        .damping(0.05f, 0.1f)
        .filter(2, 1 << 5);

GenericRigidBody compound = GenericRigidBody.compound(List.of(
        new BodyShape.Box(new Vector3f(-0.5f, 0f, 0f), new Vector3f(0.25f)),
        new BodyShape.Box(new Vector3f( 0.5f, 0f, 0f), new Vector3f(0.25f))
), 4f);

GenericRigidBody movingPlatform = GenericRigidBody.kinematic(List.of(
        new BodyShape.Box(new Vector3f(1f, 0.1f, 1f))
));
```

运行时状态方法包括 `position()`、`rotation()`、`teleport(...)`、`setLinearVelocity(...)` 和
`setAngularVelocity(...)`。`SimBody` 的 `*Ref()` 方法返回内部可变存储，只供高频适配与状态同步使用。

### 2.2 World 生命周期与固定步长

```java
try (RigidBodyWorld world = new RigidBodyWorld(
        List.of(sphere, compound), List.of(), RigidBodyWorld.DEFAULT_SUBSTEP_COUNT)) {
    world.setSimulationHertz(120f);
    world.setGravity(new Vector3f(0f, -9.80665f, 0f));
    world.addGroundPlane(0f, 0.8f, 0f);
    world.step(deltaSeconds, RigidBodyWorld.KinematicDriver.none());
}
```

对象与关节管理：

- `add(SimBody)` / `remove(SimBody)`
- `add(BallJoint)` / `remove(BallJoint)`
- `bodies()` / `joints()`
- `excludePair(bodyA, bodyB)`
- `close()`

`KinematicDriver.beginStep(world)` 在每个 fixed step 前执行；`kinematicTarget(body)` 返回同一步的目标
位置和旋转。`lastFixedStepCount()`、`droppedSimulationTime()` 与 `interpolationAlpha()` 用于诊断固定步长。

主要调参：

- `setSimulationHertz(10..1000)`
- `setSubstepCount(1..50)`
- `setMaxFixedStepsPerUpdate(1..1000)`
- `setConstraintTuning(hertz, dampingRatio)`
- `setSpeedLimits(linear, angular)`；当前 Box3D 只提供 world linear speed 映射
- `setRestitutionThreshold(value)`
- `setSleepingEnabled(boolean)` / `setSleepingThresholds(...)` / `wake()`
- `setContinuousCollisionEnabled(boolean)`
- `setCollisionsEnabled(boolean)`
- `setSelfCollisionsEnabled(boolean)`

`setSolverIterations` 是旧配置兼容入口。Box3D 不公开同名迭代数，因此它只校验和保存值。

### 2.3 Impulse、force 与 body 控制

所有向量均为世界坐标：

```java
world.applyImpulse(body, impulse);
world.applyImpulse(body, impulse, worldPoint);
world.applyAngularImpulse(body, angularImpulse);

world.applyForce(body, force);
world.applyForce(body, force, worldPoint);
world.applyTorque(body, torque);

world.setGravityScale(body, 0.25f);
float scale = world.gravityScale(body);
world.setAwake(body, true);
boolean awake = world.awake(body);
```

Impulse 立即改变速度；force/torque 由下一个 native Box3D step 积分。方法在 body 不属于该 world、
body 不是 dynamic 或输入非有限数时返回 `false`。负 gravity scale 会反转该 body 的重力。

### 2.4 Collision、contact 与 query

静态环境入口：

- `addPlaneCollider(normal, offset, friction, restitution)`
- `addGroundPlane(y, friction, restitution)`
- `addStaticBoxCollider(minimum, maximum, friction, restitution)`
- `addEnvironmentMesh(friction, restitution)`
- `setCollisionEnvironment(CollisionEnvironment)`

`StaticEnvironmentMesh` 通过 `putCell/removeCell` 增量同步 Minecraft terrain，Java 不做碰撞求解。

查询：

```java
Optional<RayHit> hit = world.raycast(origin, direction, maximumDistance);
List<BodyContact> contacts = world.contacts(body);
```

`RayHit` 返回 Java `SimBody`、世界命中点与距离。`BodyContact` 字段：

| 字段 | 含义 |
| --- | --- |
| `body` | 查询视角中的 body |
| `other` | 另一 Java body；native static terrain 时为空 |
| `point` | 世界接触点 |
| `normal` | 从 `other` 指向 `body` 的单位法线 |
| `separation` | 负值为穿透 |
| `normalImpulse` | 当前 solver point 的 normal impulse |
| `totalNormalImpulse` | 包含累计 warm-start 的 normal impulse |
| `normalVelocity` | pre-solve normal velocity |
| `touching()` | 分离量或 impulse 表明当前接触 |

汇总计数可用 `selfContactCount()` 与 `staticContactBodyCount()`。

### 2.5 Ray drag

```java
world.setDragSettings(DragSettings.DEFAULT);
world.beginDrag(hit);                 // 或 beginDrag(body, worldPoint)
world.updateDragTarget(target, frameSeconds);
world.endDrag();
```

拖拽在 native Box3D 中创建 kinematic anchor 与 spherical joint，不 teleport body，也不运行 Java 稳定器。
当前一个 world 同时只有一个 drag target。

## 3. PMX 与 glTF ragdoll API

```java
MmdRagdoll ragdoll = instance.enablePhysics();
// 或只生成 profile 中注册的主体胶囊
MmdRagdoll profiled = instance.enablePhysics(profile);
```

查询：

- `bodies()` / `joints()`
- `body(pmxRigidBodyIndex)`
- `body(Bone)` / `body(boneName)`
- `animationTarget(body)`：本帧 animation + IK 后、physics 前的 body 目标
- `raycast(...)`

`MmdRagdoll` 直接转发通用 impulse、force、torque、gravity、collision、drag、fixed-step、sleep 与
environment 控制。`Body` 另提供 `position()`、`rotation()`、`shapeSize()`、速度、`toWorldPoint(...)`、
`toLocalPoint(...)`、`teleport(...)` 和速度 setter。

模式语义：

- PMX mode 0：kinematic，跟随动画骨骼。
- PMX mode 1：dynamic，完整物理写回。
- PMX mode 2：dynamic，保留动画平移并写回物理旋转。
- profile ragdoll：PMX 与 glTF 都可按公共 UML 骨骼段生成胶囊和语义质量；默认关闭内部
  self-collision。glTF 必须提供显式 profile，不会从骨骼名猜测人体拓扑。

配置格式、profile role 与 Minecraft 部署详见 [mmd-ragdoll.md](mmd-ragdoll.md)。

## 4. IK API

`SkeletonInstance` 支持持久外部目标：

```java
skeleton.setIkTarget("右足ＩＫ", worldTarget, 1f);
skeleton.clearIkTarget("右足ＩＫ");
skeleton.clearIkTargets();

skeleton.setIkEnabled("右足ＩＫ", false);
skeleton.resetIkEnabled();
```

`weight` 范围为 `[0,1]`，在 PMX authored controller 位置与外部世界目标之间混合。方法在名称不是
有效 PMX IK controller 时返回 `false`。

`setFrameIkTarget(...)` 与 `clearFrameIkTargets()` 是 pose pipeline 的瞬态入口。它们每次求值前清空，
普通调用方通常应使用 `IkEffector` 而不是直接管理生命周期。

## 5. Pose effectors

每个 `ModelInstance` 拥有一个有序、具名的 `PoseEffectorPipeline`：

```java
PoseEffectorPipeline effectors = instance.getPoseEffectors();
effectors.add("right-hand-target", context -> {
    if (context.stage() == PoseEffector.Stage.BEFORE_IK) {
        context.skeleton().setFrameIkTarget("右手ＩＫ", target, 1f);
    }
});
effectors.remove("right-hand-target");
effectors.ids();
effectors.clear();
```

插入顺序就是执行顺序。求值使用快照，因此 effector 回调期间对 pipeline 的增删从下一阶段/次求值生效。

`PoseEvaluationContext` 提供：

- `model()`
- `skeleton()`
- `physics()`；非物理模型为空
- `stage()`
- `deltaSeconds()`

阶段约定：

- `BEFORE_IK`：写 procedural local transform 或 transient IK target。
- `AFTER_IK`：读取同帧 IK 结果并向 Box3D 提交 controller force/target。
- `AFTER_PHYSICS`：读取最终写回姿态；此阶段修改 local transform 不会自动再求一次层级。

### 5.1 `IkEffector`

```java
IkEffector hand = new IkEffector("右手ＩＫ", new Vector3f(1f, 2f, 0f), 1f);
instance.getPoseEffectors().add("hand", hand);

hand.setTarget(new Vector3f(1.2f, 2f, 0f));
hand.setTarget(target, 0.6f);
hand.setEnabled(false);
```

它只在 `BEFORE_IK` 写瞬态世界目标，不修改 authored animation 数据。

### 5.2 `ActiveRagdollEffector`

该 controller 在 `AFTER_IK` 将 dynamic body 拉向同帧动画/IK body target。它只计算 PD spring 的
force/torque，积分、碰撞、joint、sleep 仍全部由 Box3D 执行。

控制全部 dynamic body：

```java
ActiveRagdollEffector active = new ActiveRagdollEffector(
        5f,    // frequency Hz
        1f,    // damping ratio
        800f,  // maximum force
        250f   // maximum torque
);
instance.getPoseEffectors().add("active-ragdoll", active);
```

只控制指定骨骼 body：

```java
var upperBody = new ActiveRagdollEffector(
        List.of("上半身", "首", "頭"), 6f, 1f, 600f, 180f, 0.7f);
```

运行时可使用 `setWeight`、`setSettings` 与 `setEnabled`。空骨骼集合表示全部 dynamic body。

`IkEffector` 与 `ActiveRagdollEffector` 可以同时使用：前者先修改目标，PMX IK 求解链条，后者再让
Box3D body 追随 IK 后姿态。这是 animation、IK 与 active physics 的推荐组合。

## 6. glTF 动画兼容

`.glb` 与 `.gltf` 已注册为内置资源管线。parser API 位于
`lib.kasuga.rendering.models.uml.typo.gltf`：

```java
GltfAsset asset = GltfLoader.load(path);                    // 不读动画 track
GltfAsset animated = GltfLoader.load(path, Set.of("walk"));
GltfAsset all = GltfLoader.loadAllAnimations(path);
Model model = GltfModelConverter.convert(all);
```

`GltfModelConverter` 将全部 glTF node 合并到统一 UML Skeleton，并把每个 skin 的 `JOINTS_0` 重映射到
全局 Bone，所以现有 CPU/GPU skinning 与 effectors 可直接使用。Minecraft 资源管线会把 embedded
base-color 图片放入现有 texture atlas。

```java
ModelInstance instance = ...;
GltfAnimationPoseDriver animation = new GltfAnimationPoseDriver(instance);
instance.setPoseDriver(animation);
animation.play("walk", true);
```

driver 支持 translation/rotation/scale、STEP、LINEAR 与 CUBIC_SPLINE。资源包流式 `.gltf` 只支持
embedded/data URI；需要外部 `.bin` 或图片 URI 时使用 `Path` API，或打包为 `.glb`。

Minecraft 中可在模型旁放置 `<model>.gltf.json` manifest：

```json
{
  "model_scale": 1.3,
  "ragdoll": "example:ragdolls/character.json"
}
```

`model_scale` 同时作用于顶点、骨骼和生成的 ragdoll；`ragdoll` 指向 profile/模拟配置。部署逻辑只读
manifest，不按文件名或模型名称选择 profile。通用命令为：

```text
/kasuga_ragdoll deploy example:models/character.glb
```

## 7. Minecraft physics block

`MinecraftBlockRigidBody` 将 `BlockState#getCollisionShape` 的每个 voxel AABB 转成一个
body-local `BodyShape.Box`，而不是统一使用完整单位方块。因此 slab、fence、柱体和组合形状会保留真实
碰撞空隙。visual 仍由 vanilla block renderer 绘制。

`MinecraftBlockPhysics` 使用一个共享 Box3D world：

- physics blocks 之间发生真实 native collision；
- terrain 由 `MinecraftBlockRagdollEnvironment` 增量同步为 static Box3D boxes；
- local player AABB 作为 kinematic Box3D proxy；写回时只采用最深接触的受限穿透修正，并裁掉朝向
  接触面的速度分量，不会把 Box3D 已施加给 dynamic body 的累计冲量再次 `push` 给玩家；
- 默认上限 `MAX_PROPS = 256`，掉出世界或切换维度自动释放；
- 空 collision shape 的 block 不会生成。

调试命令：

```text
/kasuga_physics block [1-32]
/kasuga_physics status
/kasuga_physics clear
```

这是客户端 playground。它对本地玩家表现为可推、可站立的 collision prop，但不是服务端注册的
Block/Entity，不参与服务端权威碰撞、保存、区块序列化或多人同步。若需要“像正常方块”包含这些网络和
存档语义，应在上层增加服务端 entity 与同步协议；不要把当前 local collision bridge 当作反作弊安全边界。

## 8. 当前限制

- Box3D 没有内建 Minecraft 流体体积/浮力 API；旧 Java 浮力模型已删除。
- PMX 六轴 joint 当前映射为 spherical joint；旋转盒近似为 cone/twist，三轴平移收紧为固定 anchor。
- 一个 world 当前只有一个 drag constraint。
- glTF morph targets、第二套 skin weights、非 triangle primitive 与材质扩展尚未转换。
- glTF parser 保存 metallic/roughness factor，但当前 Minecraft adapter 只直接上传 base-color；Kasuga 的
  stylized PBR 转换仍使用 [PBR.md](PBR.md) 中的独立规则和 atlas baker。

渲染 pipeline、shader 与 effect API 见 [EFFECT_RENDERING.md](EFFECT_RENDERING.md)。
