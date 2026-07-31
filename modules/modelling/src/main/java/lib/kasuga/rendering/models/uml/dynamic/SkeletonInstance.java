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
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

@Getter
public class SkeletonInstance {

    private final ModelInstance modelInstance;
    private final Skeleton skeleton;

    private final HashMap<Bone, Transform> transforms;
    private final HashMap<Bone, Transform> absoluteTransforms;
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
        this.transform = transform != null ? transform : new Transform();
        shouldUpdate = false;
        this.transforms = new HashMap<>();
        this.absoluteTransforms = new HashMap<>();
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
        Set<Bone> updatedBones = collectUpdatedBones();
        updateQueue.clear();
        Bone rootBone = skeleton.getRoot();
        // Fix (Bug #2): 根骨骼的绝对变换应包含骨架实例变换和根骨骼自身的绑定变换。
        // 原代码: Transform transform1 = transforms.getOrDefault(rootBone, new Transform());
        // 问题: 仅从动画数据获取根骨骼变换（默认 identity），忽略了:
        //       1. this.transform（骨架实例级别变换，即 transformRoot() 设置的值）
        //       2. rootBone.getTransform()（根骨骼在PMX中定义的绑定姿态变换）
        //       这两个变换的缺失导致所有骨骼的绝对变换计算错误，蒙皮后顶点位置偏移。
        // 修复: absTransform = this.transform * rootBone.bindTransform * morph * animation。
        //       这样在绑定姿态下 abs * inv = identity（因为两边都包含 rootBindTransform 而抵消），
        //       transformRoot() API 也能正确生效。
        Transform transform1 = this.transform.copy().mul(rootBone.getTransform());
        getMorphTransform(rootBone, transform1);
        Transform anim = transforms.get(rootBone);
        if (anim != null) { transform1.mul(anim); }
        absoluteTransforms.put(rootBone, transform1);
        updateQueue.add(Pair.of(rootBone, transform1));
        recursiveUpdate();
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
                Transform anim = transforms.get(child);
                if (anim != null) {cache.mul(anim);}
                Transform t = cache.copy();
                absoluteTransforms.put(child, t);
                updateQueue.add(Pair.of(child, t));
            }
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
