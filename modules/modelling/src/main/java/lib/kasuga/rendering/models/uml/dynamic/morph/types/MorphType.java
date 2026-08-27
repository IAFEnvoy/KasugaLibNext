package lib.kasuga.rendering.models.uml.dynamic.morph.types;

public interface MorphType<MorphedElement, Identifier> {

    boolean isValidMorphInput(MorphedElement input, float percentage, float factor);

    MorphedElement morph(MorphedElement input, float percentage, float factor);

    MorphedElement getOriginal();

    Identifier getIdentifier();
}
