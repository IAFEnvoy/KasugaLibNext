package lib.kasuga.rendering.models.uml.structure.material;

import lib.kasuga.rendering.models.uml.structure.material.animators.Animator;
import lib.kasuga.rendering.models.uml.structure.material.animators.MaterialAnimation;
import lombok.Getter;

import java.util.*;

public class MaterialSetInstance {

    @Getter
    private final MaterialSet materials;

    @Getter
    private final Map<String, Animator> animators;

    @Getter
    private final Map<Material, MaterialAnimation> activeAnimations;

    public MaterialSetInstance(MaterialSet set) {
        this.materials = set;
        animators = new HashMap<>();
        activeAnimations = new HashMap<>();
    }

    public void addAnimator(String id, Animator animator) {
        animators.put(id, animator);
    }

    public boolean hasAnimator(String id) {
        return animators.containsKey(id);
    }

    public boolean removeAnimator(String id) {
        if (!animators.containsKey(id)) return false;
        Animator animator = animators.get(id);
        Set<Material> removedAnimations = new HashSet<>();
        for (Map.Entry<Material, MaterialAnimation> entry : activeAnimations.entrySet()) {
            if (entry.getValue().getAnimator() == animator) {
                removedAnimations.add(entry.getKey());
            }
        }
        for (Material mat : removedAnimations) {
            removeAnimation(mat);
        }
        return true;
    }

    public Animator getAnimator(String id) {
        return animators.get(id);
    }

    public void addAnimation(String animatorName, Material material, int startFrame) {
        MaterialAnimation anim = new MaterialAnimation(getAnimator(animatorName), material, startFrame);
        activeAnimations.put(material, anim);
    }

    public boolean hasAnimation(Material material) {
        return activeAnimations.containsKey(material);
    }

    public boolean removeAnimation(Material id) {
        if (!activeAnimations.containsKey(id)) return false;
        MaterialAnimation anim = activeAnimations.remove(id);
        try {
            anim.close();
        } catch (Exception e) {}
        return true;
    }
}
