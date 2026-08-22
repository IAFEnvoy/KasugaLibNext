package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

public class ModelTickLoop {
    @Getter
    private final TickLoopPipeline<ModelTickLoopModule> pipeline = new TickLoopPipeline<>();
    @Getter
    private final ModelInstance instance;
    /**
     * Per-node transforms passed through the tick pipeline. Slot {@code 0} is
     * the model/skeleton root transform; slot {@code i + 1} belongs to
     * {@code Skeleton.getBones()[i]}.
     */
    @Getter
    private final PendingTransform[] transforms;

    public ModelTickLoop(ModelInstance instance) {
        this(instance, createTransforms(instance));
    }

    public ModelTickLoop(ModelInstance instance, PendingTransform[] transforms) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.transforms = validateTransforms(instance, transforms);
    }

    public Model getModel() {
        return instance.getModel();
    }

    public void tick(float deltaTime) {
        this.tickWithTransforms(this.transforms, deltaTime);
    }

    public void tickWithTransforms(PendingTransform[] transforms, float deltaTime) {
        validateTransforms(instance, transforms);
        Model model = instance.getModel();
        for (var h : this.pipeline.list()) {
            h.tick(model, transforms, this, deltaTime);
        }
    }

    public PendingTransform rootTransform() {
        return transforms[0];
    }

    public PendingTransform boneTransform(int boneIndex) {
        Bone[] bones = instance.getModel().getSkeleton().getBones();
        if (boneIndex < 0 || boneIndex >= bones.length) {
            throw new IndexOutOfBoundsException("boneIndex=" + boneIndex + ", boneCount=" + bones.length);
        }
        return transforms[boneIndex + 1];
    }

    public PendingTransform boneTransform(Bone bone) {
        Objects.requireNonNull(bone, "bone");
        Bone[] bones = instance.getModel().getSkeleton().getBones();
        int index = bone.getIndex();
        if (index < 0 || index >= bones.length || bones[index] != bone) {
            throw new IllegalArgumentException("bone does not belong to this model skeleton: " + bone.getName());
        }
        return transforms[index + 1];
    }

    public void destroy() {
        Model model = instance.getModel();
        for (var h : this.pipeline.list()) {
            h.destroy(model);
        }
    }

    private static PendingTransform[] createTransforms(ModelInstance instance) {
        Objects.requireNonNull(instance, "instance");
        int size = instance.getModel().getSkeleton().getBones().length + 1;
        PendingTransform[] transforms = new PendingTransform[size];
        Arrays.setAll(transforms, ignored -> new PendingTransform());
        return transforms;
    }

    private static PendingTransform[] validateTransforms(ModelInstance instance, PendingTransform[] transforms) {
        Objects.requireNonNull(transforms, "transforms");
        int expected = instance.getModel().getSkeleton().getBones().length + 1;
        if (transforms.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " pending transforms (root + " + (expected - 1)
                            + " bones), got " + transforms.length);
        }
        for (int i = 0; i < transforms.length; i++) {
            if (transforms[i] == null) {
                throw new IllegalArgumentException("pending transform slot " + i + " is null");
            }
        }
        return transforms;
    }
}
