package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZipHelperTest {

    @Test
    void normalizesMmdTexturePaths() {
        assertEquals("tex/skin.png", ZipHelper.normalizeEntryName(".\\tex\\SKIN.PNG"));
        assertEquals("sph/gold.jpg", ZipHelper.normalizeEntryName("models/../sph//gold.jpg"));
    }
}
