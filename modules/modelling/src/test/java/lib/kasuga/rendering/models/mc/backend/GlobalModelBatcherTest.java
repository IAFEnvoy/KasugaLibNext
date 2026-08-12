package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlobalModelBatcherTest {

    @Test
    void limitsAChunkByInstanceTextureCapacity() {
        assertEquals(3, GlobalModelBatcher.nextChunkEnd(5, 0, 27, ignored -> 0));
        assertEquals(5, GlobalModelBatcher.nextChunkEnd(5, 3, 27, ignored -> 0));
    }

    @Test
    void limitsAChunkByCombinedBoneTextureCapacity() {
        assertEquals(2, GlobalModelBatcher.nextChunkEnd(4, 0, 100, ignored -> 40));
    }

    @Test
    void preservesTheSignedShortObjectIndexLimit() {
        assertEquals(Short.MAX_VALUE, GlobalModelBatcher.nextChunkEnd(
                40_000, 0, Integer.MAX_VALUE, ignored -> 0));
    }

    @Test
    void rejectsAnItemThatCannotFitInTheAvailableTextureBuffer() {
        assertThrows(IllegalStateException.class,
                () -> GlobalModelBatcher.nextChunkEnd(1, 0, 8, ignored -> 0));
        assertThrows(IllegalStateException.class,
                () -> GlobalModelBatcher.nextChunkEnd(1, 0, 100, ignored -> 101));
    }
}
