package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stable collision handle whose cells can be diffed independently. Each cell
 * owns its prebuilt colliders, so replacing one cell never rebuilds geometry
 * retained by the rest of the mesh.
 */
public final class StaticEnvironmentMesh {
    private final Map<Long, ColliderCell> cells = new HashMap<>();
    private final float friction;
    private final float restitution;
    private int solidCount;

    StaticEnvironmentMesh(float friction, float restitution) {
        this.friction = friction;
        this.restitution = restitution;
    }

    public void putCell(long key, EnvironmentCell cell) {
        EnvironmentCell value = Objects.requireNonNull(cell, "cell");
        removeCell(key);
        if (value.solids().isEmpty()) return;
        List<StaticBoxCollider> solids = new ArrayList<>(value.solids().size());
        for (EnvironmentBox solid : value.solids()) {
            solids.add(new StaticBoxCollider(solid.minimum(), solid.maximum(),
                    friction, restitution));
        }
        ColliderCell colliderCell = new ColliderCell(List.copyOf(solids));
        cells.put(key, colliderCell);
        solidCount += colliderCell.solids.size();
    }

    public void removeCell(long key) {
        ColliderCell removed = cells.remove(key);
        if (removed == null) return;
        solidCount -= removed.solids.size();
    }

    public void clear() {
        cells.clear();
        solidCount = 0;
    }

    public int cellCount() { return cells.size(); }
    public int solidCount() { return solidCount; }

    Iterable<ColliderCell> colliderCells() { return cells.values(); }

    static final class ColliderCell {
        final List<StaticBoxCollider> solids;

        ColliderCell(List<StaticBoxCollider> solids) {
            this.solids = solids;
        }
    }
}
