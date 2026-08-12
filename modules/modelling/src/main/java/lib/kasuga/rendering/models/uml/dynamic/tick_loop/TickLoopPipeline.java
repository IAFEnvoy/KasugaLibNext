package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TickLoopPipeline<I> {
    private final Map<String, I> handlers = new HashMap<>();
    private final List<String> orderList = new ArrayList<>();
    private final ArrayList<I> compiled = new ArrayList<>();

    public void update() {
        this.compiled.clear();

        for (var id : this.orderList) {
            this.compiled.add(this.handlers.get(id));
        }
    }

    public boolean contains(String id) {
        return this.handlers.containsKey(id);
    }

    public void addLast(String id, I item) {
        this.handlers.put(id, item);
        this.orderList.add(id);
        this.update();
    }

    public void addFirst(String id, I item) {
        this.handlers.put(id, item);
        this.orderList.add(0, id);
        this.update();
    }

    private void _insert(String target, String id, int offset, I item) {
        if (!this.handlers.containsKey(target)) {
            throw new IllegalArgumentException("Unknown target: " + target);
        }

        this.handlers.put(id, item);
        this.orderList.add(this.orderList.indexOf(target) + offset, id);
        this.update();
    }

    public void addBefore(String target, String id, I item) {
        this._insert(target, id, -1, item);
    }

    public void addAfter(String target, String id, I item) {
        this._insert(target, id, 1, item);
    }

    public void remove(String id) {
        this.handlers.remove(id);
        this.orderList.remove(id);
        this.update();
    }

    public List<I> list() {
        return this.compiled;
    }

    public void clear() {
        this.handlers.clear();
        this.orderList.clear();
        this.update();
    }
}
