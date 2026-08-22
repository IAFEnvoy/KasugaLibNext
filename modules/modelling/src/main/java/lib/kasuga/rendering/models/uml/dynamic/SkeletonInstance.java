package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonInstanceData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.IKLimitation;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.ParentBoneInherit;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxIKBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxIKChain;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

@Getter
public class SkeletonInstance {

    private final ModelInstance modelInstance;
    private final Skeleton skeleton;
    private final Bone[] pmxBones;

    private final HashMap<Bone, Transform> transforms;
    private final HashMap<Bone, Transform> absoluteTransforms;
    private final HashMap<Bone, Transform> evaluatedTransforms;
    private final HashMap<Bone, Transform> ikTransforms;
    private final HashMap<Bone, Transform> physicsTransforms;
    private final HashMap<String, Boolean> ikEnabled;
    private final HashMap<Bone, IkTarget> ikTargets;
    private final HashMap<Bone, IkTarget> frameIkTargets;
    private final Set<Bone> dirtyBones;
    private Set<Bone> lastDirtyBones;

    private final Queue<Pair<Bone, Transform>> updateQueue = new LinkedList<>();

    @NonNull
    private Transform transform;

    private SkeletonInstanceData data;

    private boolean shouldUpdate;
    private boolean fullUpdateRequested;
    private boolean lastFullUpdate;
    private long version;

    public SkeletonInstance(ModelInstance instance, Skeleton skeleton, @Nullable Transform transform, @Nullable SkeletonInstanceData data) {
        this.modelInstance = instance;
        this.skeleton = skeleton;
        this.pmxBones = Arrays.stream(skeleton.getBones())
                .filter(bone -> bone.getBoneData() instanceof PmxBone)
                .toArray(Bone[]::new);
        this.transform = transform != null ? transform : new Transform();
        shouldUpdate = false;
        this.transforms = new HashMap<>();
        this.absoluteTransforms = new HashMap<>();
        this.evaluatedTransforms = new HashMap<>();
        this.ikTransforms = new HashMap<>();
        this.physicsTransforms = new HashMap<>();
        this.ikEnabled = new HashMap<>();
        this.ikTargets = new HashMap<>();
        this.frameIkTargets = new HashMap<>();
        this.dirtyBones = new HashSet<>();
        this.lastDirtyBones = Collections.emptySet();
        this.data = data;
        this.fullUpdateRequested = true;
        this.lastFullUpdate = true;
        this.version = 0;
        updateTransform();
    }

    public void getMorphTransform(Bone original, Transform dest) {
        Transform t = modelInstance.getMorph().getCachedTransform(original);
        if (t == null) return;
        dest.mul(t);
    }

    public void updateTransform() {
        updateTransform(true);
    }

    /**
     * Rebuilds the final hierarchy after physics has replaced some local bone
     * transforms. The IK corrections were already solved while evaluating the
     * animation target, so solving them again here is both redundant and can
     * fight the physical pose.
     */
    public void updateTransformAfterPhysics() {
        updateTransform(false);
    }

    private void updateTransform(boolean solveIk) {
        Set<Bone> updatedBones = collectUpdatedBones();
        if (solveIk) ikTransforms.clear();
        evaluateHierarchy();
        if (solveIk) solvePmxIk();
        lastFullUpdate = fullUpdateRequested || updatedBones.isEmpty();
        lastDirtyBones = lastFullUpdate ? Collections.emptySet() : updatedBones;
        dirtyBones.clear();
        fullUpdateRequested = false;
        shouldUpdate = false;
        version++;
    }

    public boolean checkShouldUpdate() {
        this.shouldUpdate = shouldUpdate || isMorphUpdated();
        return this.shouldUpdate;
    }

    public void setShouldUpdate(boolean shouldUpdate) {
        this.shouldUpdate = shouldUpdate;
        if (shouldUpdate) {
            requestFullUpdate();
        }
    }

    public boolean transform(String boneName, Transform transform) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        transforms.put(bone, transform);
        markDirty(bone);
        return true;
    }

    public boolean transform(Bone bone, Transform transform) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        transforms.put(bone, transform);
        markDirty(bone);
        return true;
    }

    public boolean mulTransform(String boneName, Transform transform) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(transform);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean mulTransform(Bone bone, Transform transform) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(transform);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean offset(String boneName, Vector3f offset) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.translate(offset);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean offset(Bone bone, Vector3f offset) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.translate(offset);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean rotate(String boneName, Quaternionf rotation) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(rotation);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean rotate(Bone bone, Quaternionf rotation) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.mul(rotation);
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean scale(String boneName, Vector3f scale) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.scale(scale.x(), scale.y(), scale.z());
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean scale(Bone bone, Vector3f scale) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        Transform current = transforms.getOrDefault(bone, new Transform());
        current.scale(scale.x(), scale.y(), scale.z());
        transforms.put(bone, current);
        markDirty(bone);
        return true;
    }

    public boolean reset(String boneName) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null) return false;
        transforms.remove(bone);
        markDirty(bone);
        return true;
    }

    public boolean reset(Bone bone) {
        if (!skeleton.getBoneMap().containsValue(bone)) return false;
        transforms.remove(bone);
        markDirty(bone);
        return true;
    }

    public boolean resetAll() {
        if (transforms.isEmpty()) return false;
        transforms.clear();
        requestFullUpdate();
        return true;
    }

    public void transformRoot(@NonNull Transform transform) {
        this.transform = transform;
        requestFullUpdate();
    }

    public void mulTransformRoot(@NonNull Transform transform) {
        this.transform.mul(transform);
        requestFullUpdate();
    }

    public void offsetRoot(@NonNull Vector3f offset) {
        this.transform.translate(offset);
        requestFullUpdate();
    }

    public void rotateRoot(@NonNull Quaternionf rotation) {
        this.transform.mul(rotation);
        requestFullUpdate();
    }

    public void scaleRoot(@NonNull Vector3f scale) {
        this.transform.scale(scale.x(), scale.y(), scale.z());
        requestFullUpdate();
    }

    public void resetRoot() {
        this.transform = new Transform();
        requestFullUpdate();
    }

    public void tick() {
        if (shouldUpdate) {
            updateTransform();
            shouldUpdate = false;
        }
    }

    public boolean isBindPose() {
        return transforms.isEmpty() && transform.isIdentity();
    }

    public boolean setIkEnabled(String boneName, boolean enabled) {
        Bone bone = skeleton.getBoneMap().get(boneName);
        if (bone == null || !(bone.getBoneData() instanceof PmxBone pmx) || pmx.ik == null) return false;
        ikEnabled.put(boneName, enabled);
        requestFullUpdate();
        return true;
    }

    public void resetIkEnabled() {
        if (ikEnabled.isEmpty()) return;
        ikEnabled.clear();
        requestFullUpdate();
    }

    public boolean isIkEnabled(String boneName) {
        return ikEnabled.getOrDefault(boneName, true);
    }

    /** Sets a persistent world-space target for a PMX IK controller. */
    public boolean setIkTarget(String controllerBone, Vector3f worldTarget, float weight) {
        Bone bone = ikController(controllerBone);
        if (bone == null) return false;
        ikTargets.put(bone, ikTarget(worldTarget, weight));
        requestFullUpdate();
        return true;
    }

    public boolean clearIkTarget(String controllerBone) {
        Bone bone = skeleton.getBoneMap().get(controllerBone);
        if (bone == null || ikTargets.remove(bone) == null) return false;
        requestFullUpdate();
        return true;
    }

    public void clearIkTargets() {
        if (ikTargets.isEmpty()) return;
        ikTargets.clear();
        requestFullUpdate();
    }

    /** Sets an IK target valid only for the current pose-pipeline evaluation. */
    public boolean setFrameIkTarget(String controllerBone, Vector3f worldTarget, float weight) {
        Bone bone = ikController(controllerBone);
        if (bone == null) return false;
        frameIkTargets.put(bone, ikTarget(worldTarget, weight));
        return true;
    }

    /** Called by the model pose pipeline before its BEFORE_IK effectors. */
    public void clearFrameIkTargets() {
        frameIkTargets.clear();
    }

    private Bone ikController(String name) {
        Bone bone = skeleton.getBoneMap().get(name);
        return bone != null && bone.getBoneData() instanceof PmxBone pmx && pmx.ik != null
                ? bone : null;
    }

    private static IkTarget ikTarget(Vector3f target, float weight) {
        Vector3f position = new Vector3f(Objects.requireNonNull(target, "target"));
        if (!position.isFinite() || !Float.isFinite(weight) || weight < 0f || weight > 1f) {
            throw new IllegalArgumentException("IK target must be finite and weight within [0, 1]");
        }
        return new IkTarget(position, weight);
    }

    private void evaluateHierarchy() {
        updateQueue.clear();
        evaluatedTransforms.clear();
        absoluteTransforms.clear();
        Bone rootBone = skeleton.getRoot();
        Transform rootLocal = evaluatedLocalTransform(rootBone);
        Transform rootAbsolute = transform.copy().mul(rootBone.getTransform());
        getMorphTransform(rootBone, rootAbsolute);
        rootAbsolute.mul(rootLocal);
        absoluteTransforms.put(rootBone, rootAbsolute);
        updateQueue.add(Pair.of(rootBone, rootAbsolute));
        recursiveUpdate();
    }

    private void recursiveUpdate() {
        Transform cache = new Transform();
        while (!updateQueue.isEmpty()) {
            Pair<Bone, Transform> boneTransformPair = updateQueue.poll();
            Bone bone = boneTransformPair.getFirst();
            Transform parentTransform = boneTransformPair.getSecond();

            Bone[] children = bone.getChildren();
            if (children == null) continue;
            for (Bone child : children) {
                if (child == null) continue;
                cache.set(child.getTransform());
                getMorphTransform(child, cache);
                cache = parentTransform.copy().mul(cache);
                cache.mul(evaluatedLocalTransform(child));
                Transform t = cache.copy();
                absoluteTransforms.put(child, t);
                updateQueue.add(Pair.of(child, t));
            }
        }
    }

    private Transform evaluatedLocalTransform(Bone bone) {
        Transform result = transforms.getOrDefault(bone, new Transform()).copy();
        if (bone.getBoneData() instanceof PmxBone pmx) {
            applyGrant(result, pmx);
            applyFixedAxis(result, pmx);
        }
        Transform ik = ikTransforms.get(bone);
        if (ik != null) result.mul(ik);
        Transform physics = physicsTransforms.get(bone);
        if (physics != null) result.set(physics);
        evaluatedTransforms.put(bone, result.copy());
        return result;
    }

    /**
     * Removes the last simulated pose so animation and IK can be evaluated as
     * the kinematic target for the next physics step.
     */
    public void clearPhysicsTransforms() {
        if (physicsTransforms.isEmpty()) return;
        physicsTransforms.clear();
        requestFullUpdate();
    }

    /** Applies a complete set of physics-produced local bone transforms. */
    public void applyPhysicsTransforms(Map<Bone, Transform> pose) {
        physicsTransforms.clear();
        pose.forEach((bone, value) -> {
            if (skeleton.getBoneTransforms().containsKey(bone)) {
                physicsTransforms.put(bone, value.copy());
            }
        });
        requestFullUpdate();
    }

    private void applyGrant(Transform result, PmxBone pmx) {
        ParentBoneInherit inherit = pmx.inherit;
        if (inherit == null) return;
        Bone source = pmxBone(inherit.parentIndex().intValue());
        if (source == null) return;
        Transform sourceTransform = evaluatedTransforms.getOrDefault(source,
                transforms.getOrDefault(source, new Transform()));
        float weight = inherit.weight();
        if (pmx.flags.inheritParentTranslation) {
            result.translateWorld(new Vector3f(sourceTransform.getPosition()).mul(weight));
        }
        if (pmx.flags.inheritParentRotation) {
            result.mul(new Quaternionf().identity().slerp(sourceTransform.getRotation(), weight));
        }
    }

    private void applyFixedAxis(Transform result, PmxBone pmx) {
        if (!pmx.flags.isAxisFixed || pmx.fixedAxis == null || pmx.fixedAxis.lengthSquared() < 1e-8f) return;
        Vector3f axis = new Vector3f(pmx.fixedAxis).normalize();
        Quaternionf rotation = result.getRotation();
        Vector3f vector = new Vector3f(rotation.x, rotation.y, rotation.z);
        float projection = vector.dot(axis);
        Quaternionf twist = new Quaternionf(axis.x * projection, axis.y * projection,
                axis.z * projection, rotation.w).normalize();
        result.set(new org.joml.Matrix4f().translationRotateScale(
                result.getPosition(), twist, result.transform().getScale(new Vector3f())));
    }

    private void solvePmxIk() {
        for (Bone controller : skeleton.getBones()) {
            if (!(controller.getBoneData() instanceof PmxBone pmx) || pmx.ik == null
                    || !isIkEnabled(controller.getName())) continue;
            solveIk(controller, pmx.ik);
        }
    }

    private void solveIk(Bone controller, PmxIKBone ik) {
        Bone effector = pmxBone(ik.boneIndex.intValue());
        if (effector == null || absoluteTransforms.get(controller) == null) return;
        Vector3f authoredTarget = absoluteTransforms.get(controller).getPosition();
        IkTarget override = frameIkTargets.getOrDefault(controller, ikTargets.get(controller));
        Vector3f targetPosition = override == null ? authoredTarget
                : authoredTarget.lerp(override.position, override.weight, new Vector3f());
        int iterations = Math.min(Math.max(ik.CCD_Count, 0), 256);
        boolean corrected = false;
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean converged = false;
            for (PmxIKChain chain : ik.chains) {
                Bone link = pmxBone(chain.boneIndex.intValue());
                if (link == null || absoluteTransforms.get(link) == null) continue;
                Vector3f linkPosition = absoluteTransforms.get(link).getPosition();
                Vector3f effectorDirection = absoluteTransforms.get(effector).getPosition()
                        .sub(linkPosition, new Vector3f());
                Vector3f targetDirection = new Vector3f(targetPosition)
                        .sub(linkPosition, new Vector3f());
                if (effectorDirection.lengthSquared() < 1e-10f || targetDirection.lengthSquared() < 1e-10f) continue;
                effectorDirection.normalize();
                targetDirection.normalize();
                float angle = (float) Math.acos(Math.clamp(effectorDirection.dot(targetDirection), -1f, 1f));
                angle = Math.min(angle, Math.abs(ik.boneRotationLimit));
                if (angle < 1e-6f) continue;
                Vector3f axis = effectorDirection.cross(targetDirection, new Vector3f());
                if (axis.lengthSquared() < 1e-10f) {
                    if (effectorDirection.dot(targetDirection) > 0f) continue;
                    axis = Math.abs(effectorDirection.x) < 0.9f
                            ? effectorDirection.cross(new Vector3f(1, 0, 0), axis)
                            : effectorDirection.cross(new Vector3f(0, 1, 0), axis);
                }
                Quaternionf worldDelta = new Quaternionf(new AxisAngle4f(angle, axis.normalize()));
                Quaternionf worldRotation = absoluteTransforms.get(link).getRotation();
                Quaternionf delta = new Quaternionf(worldRotation).invert()
                        .mul(worldDelta)
                        .mul(worldRotation)
                        .normalize();
                Transform correction = ikTransforms.computeIfAbsent(link, ignored -> new Transform());
                correction.mul(delta);
                if (chain.useRotationLimit && chain.limit != null) clampIk(correction, chain.limit);
                corrected = true;
                evaluateHierarchyFrom(link, effector);
                if (absoluteTransforms.get(effector).getPosition()
                        .distanceSquared(targetPosition) < 1e-8f) {
                    converged = true;
                    break;
                }
            }
            if (converged) break;
        }
        // Refresh grant dependencies and other branches once per controller.
        // Previously this full traversal happened after every chain correction.
        if (corrected) evaluateHierarchy();
    }

    private void evaluateHierarchyFrom(Bone root, Bone requiredDescendant) {
        if (!isAncestorOf(root, requiredDescendant)) {
            evaluateHierarchy();
            return;
        }
        Bone parent = root.getParent();
        if (parent == null) {
            evaluateHierarchy();
            return;
        }
        Transform parentAbsolute = absoluteTransforms.get(parent);
        if (parentAbsolute == null) {
            evaluateHierarchy();
            return;
        }

        updateQueue.clear();
        Transform rootAbsolute = parentAbsolute.copy().mul(root.getTransform());
        getMorphTransform(root, rootAbsolute);
        rootAbsolute.mul(evaluatedLocalTransform(root));
        absoluteTransforms.put(root, rootAbsolute);
        updateQueue.add(Pair.of(root, rootAbsolute));
        recursiveUpdate();
    }

    private static boolean isAncestorOf(Bone ancestor, Bone bone) {
        for (Bone current = bone; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private void clampIk(Transform correction, IKLimitation limit) {
        Vector3f euler = correction.getRotation().getEulerAnglesXYZ(new Vector3f());
        euler.set(
                Math.clamp(euler.x, limit.min().x, limit.max().x),
                Math.clamp(euler.y, limit.min().y, limit.max().y),
                Math.clamp(euler.z, limit.min().z, limit.max().z));
        correction.set(new org.joml.Matrix4f().rotationXYZ(euler.x, euler.y, euler.z));
    }

    private Bone pmxBone(int pmxIndex) {
        return pmxIndex >= 0 && pmxIndex < pmxBones.length ? pmxBones[pmxIndex] : null;
    }

    private record IkTarget(Vector3f position, float weight) {
        private IkTarget {
            position = new Vector3f(position);
        }
    }

    private void markDirty(Bone bone) {
        dirtyBones.add(bone);
        shouldUpdate = true;
    }

    private void requestFullUpdate() {
        shouldUpdate = true;
        fullUpdateRequested = true;
        dirtyBones.clear();
    }

    public boolean isMorphUpdated() {
        return !modelInstance.getMorph().getLastUpdatedBones().isEmpty();
    }

    private Set<Bone> collectUpdatedBones() {
        BitSet lastUpdated = modelInstance.getMorph().getLastUpdatedBones();
        Set<Bone> newlyUpdatedBones = new HashSet<>();
        for (int i = lastUpdated.nextSetBit(0); i >= 0; i = lastUpdated.nextSetBit(i + 1)) {
            newlyUpdatedBones.add(skeleton.getBones()[i]);
        }
        lastUpdated.clear();
        if (!newlyUpdatedBones.isEmpty()) {
            for (Bone bone : newlyUpdatedBones) {
                if (!getSkeleton().getBoneMap().containsValue(bone)) continue;
                dirtyBones.add(bone);
            }
            newlyUpdatedBones.clear();
        }
        if (fullUpdateRequested || dirtyBones.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Bone> updatedBones = new HashSet<>();
        for (Bone bone : dirtyBones) {
            collectSubtree(bone, updatedBones);
        }
        return Collections.unmodifiableSet(updatedBones);
    }

    private void collectSubtree(Bone bone, Set<Bone> result) {
        if (bone == null || !result.add(bone) || bone.getChildren() == null) {
            return;
        }
        for (Bone child : bone.getChildren()) {
            collectSubtree(child, result);
        }
    }

    public void collectBoneContexts(List<BoneContext> contexts, Vertex vertex) {
        contexts.clear();
        for (Pair<Bone, Float> weight : vertex.getBinding().getWeights()) {
            Bone bone = weight.getFirst();
            float w = weight.getSecond();
            Transform transform = transforms.getOrDefault(bone, new Transform());
            Transform absTransform = absoluteTransforms.get(bone);
            Pair<Transform, Transform> pair = skeleton.getBoneTransforms().get(bone);
            contexts.add(new BoneContext<>(bone, w, bone.getBoneData(), transform,
                    pair.getFirst(), absTransform, pair.getSecond()));
        }
    }

    public HashMap<Vertex, Vertex> getVertexTransforms(Model model, Bridge bridge) {
        HashMap<Vertex, Vertex> vertexTransforms = new HashMap<>();
        List<BoneContext> contexts = new ArrayList<>();
        for (Vertex vertex : model.getVertices()) {
            BoneBindingFunc func = bridge.getBoneBindingFunc(model, this, vertex);
            if (func == null) continue;
            collectBoneContexts(contexts, vertex);
            Vertex result = func.apply(vertex, contexts);
            vertexTransforms.put(vertex, result);
        }
        return vertexTransforms;
    }
}
