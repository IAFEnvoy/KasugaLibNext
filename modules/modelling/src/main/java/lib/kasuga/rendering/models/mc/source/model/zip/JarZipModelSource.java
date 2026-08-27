package lib.kasuga.rendering.models.mc.source.model.zip;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class JarZipModelSource extends ZipModelSource<ResourceLocation> {


    public JarZipModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<ZipHelper> getInput(ResourceLocation input) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation location = ResourceLocation.tryBuild(
                input.getNamespace(), input.getPath()
        );
        List<Resource> resources = manager.getResourceStack(location);
        if (resources.isEmpty()) return Optional.empty();
        Resource resource = resources.getFirst();
        ResourceLocation metaLocation = ResourceLocation.tryBuild(
                input.getNamespace(), input.getPath().replace(".zip", ".json")
        );
        Optional<Resource> metaResource = manager.getResource(metaLocation);
        Charset charset = StandardCharsets.UTF_8;
        if (metaResource.isPresent()) {
            Resource meta = metaResource.get();
            try {
                JsonElement json = JsonParser.parseReader(meta.openAsReader());
                if (json.isJsonObject()) {
                    ZipMeta metaData = new ZipMeta(json.getAsJsonObject());
                    charset = metaData.getCharset();
                }
            } catch (Exception ignored) {}
        }
        try {
            return Optional.ofNullable(ZipHelper.fromResource(location, resource, charset));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<ResourceLocation> getInputType() {
        return ResourceLocation.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ResourceLocation;
    }
}
