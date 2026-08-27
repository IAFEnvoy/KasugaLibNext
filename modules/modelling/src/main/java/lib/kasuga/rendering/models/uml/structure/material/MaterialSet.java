package lib.kasuga.rendering.models.uml.structure.material;

import lib.kasuga.rendering.models.uml.structure.material.animators.Animator;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;

public class MaterialSet {

    @Getter
    @NonNull
    private final Texture[] textures;

    @Getter
    @NonNull
    private final Material[] materials;

    public MaterialSet(@NotNull Texture texture, @NotNull Material material) {
        this.textures = new Texture[]{texture};
        this.materials = new Material[]{material};
    }

    public MaterialSet(Collection<Texture> textures, @NonNull Collection<Material> materials) {
        this.textures = textures.toArray(new Texture[0]);
        this.materials = materials.toArray(new Material[0]);
    }
}
