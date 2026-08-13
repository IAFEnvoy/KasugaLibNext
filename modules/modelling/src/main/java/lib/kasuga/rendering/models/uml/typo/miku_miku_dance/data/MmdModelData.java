package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data;

import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;

import java.util.Objects;

/** PMX/converted-PMD metadata retained on each built model for UI and physics backends. */
public record MmdModelData(PmxHeader header, PmxTail tail) implements ModelData {
    public MmdModelData {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(tail, "tail");
    }

    @Override
    public boolean isMeshTriangles() {
        return true;
    }
}
