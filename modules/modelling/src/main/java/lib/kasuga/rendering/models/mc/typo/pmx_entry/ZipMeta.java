package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import com.google.gson.JsonObject;
import lombok.Getter;

import java.nio.charset.Charset;

public class ZipMeta {

    @Getter
    private Charset charset;

    public ZipMeta(JsonObject json) {
        try {
            charset = Charset.forName(json.get("encoding").getAsString());
        } catch (Exception e) {
            charset = Charset.defaultCharset();
        }
    }
}
