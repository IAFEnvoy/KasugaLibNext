package lib.kasuga.rendering.models.mc.source.texture;

import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.structure.Pair;
import lombok.NonNull;
import net.minecraft.Util;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

public class CombinedTextureManager extends KasugaTextureManager {

    protected final TextureAtlas normalMap;
    protected final TextureAtlas specularMap;
    private final Map<Object, SpriteUploader<?>[]> spriteUploaders;
    private final Map<Object, SpriteContents[]> caches;
    private final Map<Object, TextureAtlasSprite[]> loadedSprites;
    private final Function<ResourceLocation, SpriteContents>[] missingImages;
    private final DefaultSpriteSupplier[] defaultSprites;

    public interface DefaultSpriteSupplier {
        SpriteContents get(ResourceLocation rl, int width, int height);
    }

    public CombinedTextureManager(SourceType type, String name,
                                  TextureManager textureManager,
                                  ResourceLocation textureAtlasLocation,
                                  @Nullable Function<ResourceLocation, SpriteContents> missingImage,
                                  ResourceLocation normalMapLocation,
                                  @Nullable Function<ResourceLocation, SpriteContents> missingNormalMapImage,
                                  @NonNull DefaultSpriteSupplier defaultNormalSprite,
                                  ResourceLocation specularMapLocation,
                                  @Nullable Function<ResourceLocation, SpriteContents> missingSpecularMapImage,
                                  @NonNull DefaultSpriteSupplier defaultSpecularSprite) {
        super(type, name, textureManager, textureAtlasLocation, missingImage);
        this.normalMap = new TextureAtlas(normalMapLocation);
        this.specularMap = new TextureAtlas(specularMapLocation);
        this.spriteUploaders = new HashMap<>();
        this.caches = new HashMap<>();
        this.loadedSprites = new HashMap<>();
        this.missingImages = new Function[]{missingImage, missingNormalMapImage, missingSpecularMapImage};
        this.defaultSprites = new DefaultSpriteSupplier[]{defaultNormalSprite, defaultSpecularSprite};
        textureManager.register(this.normalMap.location(), this.normalMap);
        textureManager.register(this.specularMap.location(), this.specularMap);
    }

    @Override
    public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller preparationsProfiler, @NotNull ProfilerFiller reloadProfiler, @NotNull Executor backgroundExecutor, @NotNull Executor gameExecutor) {
        SpriteLoader[] loaders = new SpriteLoader[]{
                SpriteLoader.create(this.textureAtlas),
                SpriteLoader.create(this.normalMap),
                SpriteLoader.create(this.specularMap)
        };
        CompletableFuture<SpriteLoader.Preparations[]> preparations = combinedLoadAndStitch(
                loaders, 0, backgroundExecutor
        );
        return preparations.thenCompose(param -> {
            List<CompletableFuture<SpriteLoader.Preparations>> l = new ArrayList<>(3);
            for (SpriteLoader.Preparations p : param) {
                l.add(p.waitForUpload());
            }
            return Util.sequence(l);
        }).thenCompose(preparationBarrier::wait)
                .thenAcceptAsync(param -> this.apply(param, reloadProfiler), gameExecutor)
                .thenAcceptAsync(any -> this.spriteUploaders.clear(), gameExecutor)
                .thenAcceptAsync(any -> this.caches.forEach((k, v) -> {
                    TextureAtlasSprite[] sprites = new TextureAtlasSprite[v.length];
                    for (int i = 0; i < v.length; i++) {
                        SpriteContents content = v[i];
                        Objects.requireNonNull(content);
                        for (TextureAtlasSprite sprite : getAtlas(i).getTextures().values()) {
                            if (sprite.contents().equals(content)) {
                                sprites[i] = sprite;
                                break;
                            }
                        }
                        Objects.requireNonNull(sprites[i], "Failed to find sprite for content: " + content.name());
                    }
                    loadedSprites.put(k, sprites);
                })).thenAcceptAsync(any -> this.caches.clear(), gameExecutor);
    }

    @Override
    @Deprecated
    protected CompletableFuture<SpriteLoader.Preparations> loadAndStitch(SpriteLoader loader, int mipLevel, Executor executor) {
        throw new UnsupportedOperationException("Use combinedLoadAndStitch instead for combined texture manager");
    }

    protected CompletableFuture<SpriteLoader.Preparations[]> combinedLoadAndStitch(SpriteLoader[] loaders, int mipLevel, Executor executor) {
        return CompletableFuture.supplyAsync(this::combinedList, executor)
                .thenCompose(list -> runCombinedSuppliers(list, executor))
                .thenApply(param -> {
                    List<SpriteContents> textures = new ArrayList<>();
                    List<SpriteContents> normalMaps = new ArrayList<>();
                    List<SpriteContents> mrMaps = new ArrayList<>();
                    for (SpriteContents[] contents : param) {
                        textures.add(contents[0]);
                        normalMaps.add(contents[1]);
                        mrMaps.add(contents[2]);
                    }
                    SpriteLoader.Preparations[] preparations = new SpriteLoader.Preparations[3];
                    preparations[0] = loaders[0].stitch(textures, mipLevel, executor);
                    preparations[1] = loaders[1].stitch(normalMaps, mipLevel, executor);
                    preparations[2] = loaders[2].stitch(mrMaps, mipLevel, executor);
                    return preparations;
                });
    }

    @Override
    @Deprecated
    protected List<Supplier<Pair<Object, SpriteContents>>> list() {
        throw new UnsupportedOperationException("Use combinedList instead for combined texture manager");
    }

    protected List<Supplier<Pair<Object, SpriteContents[]>>> combinedList() {
        ResourceLocation missingNo = MissingTextureAtlasSprite.getLocation();
        List<Supplier<Pair<Object, SpriteContents[]>>> combined = new ArrayList<>();
        combined.add(() -> Pair.of(missingNo, new SpriteContents[]{
                missingImages[0] != null ? missingImages[0].apply(missingNo) : MissingTextureAtlasSprite.create(),
                missingImages[1] != null ? missingImages[1].apply(missingNo) : MissingTextureAtlasSprite.create(),
                missingImages[2] != null ? missingImages[2].apply(missingNo) : MissingTextureAtlasSprite.create()
        }));
        combined.add(() -> Pair.of(RenderState.DEFAULT_TRANSPARENCY, new SpriteContents[] {
                RenderState.createTransparencyDefaultSprite(),
                RenderState.createTransparencyDefaultSprite(),
                RenderState.createTransparencyDefaultSprite()
        }));
        for (SpriteUploader<?>[] uploader : spriteUploaders.values()) {
            combined.add(() -> {
                Object identifier = uploader[0].getResourceIdentifier();
                Objects.requireNonNull(uploader[0]);
                SpriteContents content = uploader[0].loadSprite(null, null);
                Objects.requireNonNull(content);
                int width = content.width(), height = content.height();
                @Nullable SpriteContents normalContent, specularContent;
                if (uploader[1] == null) {
                    normalContent = defaultSprites[0].get(content.name(), width, height);
                } else {
                    normalContent = uploader[1].loadSprite(null, null);
                    if (normalContent == null) {
                        normalContent = defaultSprites[0].get(content.name(), width, height);
                    }
                }
                if (uploader[2] == null) {
                    specularContent = defaultSprites[1].get(content.name(), width, height);
                } else {
                    specularContent = uploader[2].loadSprite(null, null);
                    if (specularContent == null) {
                        specularContent = defaultSprites[1].get(content.name(), width, height);
                    }
                }
                Objects.requireNonNull(normalContent);
                Objects.requireNonNull(specularContent);
                if (content.width() != normalContent.width() || content.height() != normalContent.height()) {
                    throw new IllegalStateException("Texture and normal map dimensions do not match for " + identifier);
                }

                if (content.width() != specularContent.width() || content.height() != specularContent.height()) {
                    throw new IllegalStateException("Texture and metallic-roughness map dimensions do not match for " + identifier);
                }
                return Pair.of(identifier, new SpriteContents[]{content, normalContent, specularContent});
            });
        }
        return combined;
    }

    @Override
    @Deprecated
    public CompletableFuture<List<SpriteContents>> runSpriteSuppliers(List<Supplier<Pair<Object, SpriteContents>>> factories, Executor executor) {
        throw new UnsupportedOperationException("Use runCombinedSuppliers instead for combined texture manager");
    }

    public CompletableFuture<List<SpriteContents[]>> runCombinedSuppliers(List<Supplier<Pair<Object, SpriteContents[]>>> factories, Executor executor) {
        List<CompletableFuture<SpriteContents[]>> list = factories.stream().map(
                element -> CompletableFuture.supplyAsync(
                        () -> {
                            Pair<Object, SpriteContents[]> pair = element.get();
                            Object identifier = pair.getFirst();
                            SpriteContents[] contents = pair.getSecond();
                            caches.put(identifier, contents);
                            return contents;
                        }, executor
                )
        ).toList();
        return Util.sequence(list).thenApply(param -> param.stream().filter(Objects::nonNull).toList());
    }

    protected void apply(List<SpriteLoader.Preparations> preparations, ProfilerFiller profiler) {
        profiler.startTick();
        profiler.push("upload");
        for (int i = 0; i < 3; i++) {this.getAtlas(i).upload(preparations.get(i));}
        profiler.pop();
        profiler.endTick();
    }

    @Override
    @Deprecated
    protected void apply(SpriteLoader.Preparations preparations, ProfilerFiller profiler) {
        throw new UnsupportedOperationException("Use apply(List<SpriteLoader.Preparations>) instead for combined texture manager");
    }

    @Override
    public Optional<InputStream> load(@NonNull Object sourceIdentifier) {
        return this.load(sourceIdentifier, null, null);
    }


    public Optional<InputStream> load(@NonNull Object sourceIdentifier,
                                      @Nullable Object normalMapIdentifier,
                                      @Nullable Object specularMapIdentifier) {
        if (this.spriteUploaders.containsKey(sourceIdentifier)) {
            return Optional.empty();
        }
        for (Source<?, ?> source : getSources().values()) {
            if (source.isValidInput(sourceIdentifier)) {
                this.spriteUploaders.put(sourceIdentifier, new SpriteUploader[]{
                        new SpriteUploader(this, (TextureSource) source, sourceIdentifier),
                        normalMapIdentifier != null ? new SpriteUploader<>(this,(TextureSource) source, normalMapIdentifier) : null,
                        specularMapIdentifier != null ? new SpriteUploader<>(this,(TextureSource) source, specularMapIdentifier) : null
                });
                return Optional.empty();
            }
        }
        throw new IllegalArgumentException("No source found for identifier: " + sourceIdentifier);
    }

    @Override
    public TextureAtlasSprite get(Object identifier) {
        TextureAtlasSprite[] sprites = loadedSprites.get(identifier);
        if (sprites == null) return null;
        return sprites[0];
    }

    @Override
    public @NonNull TextureAtlas getTextureAtlas() {
        return super.getTextureAtlas();
    }

    public @NonNull TextureAtlas getNormalMap() {
        return normalMap;
    }

    public @NonNull TextureAtlas getSpecularMap() {
        return specularMap;
    }

    public @NonNull TextureAtlas getAtlas(int index) {
        return switch (index) {
            case 0 -> getTextureAtlas();
            case 1 -> getNormalMap();
            case 2 -> getSpecularMap();
            default -> throw new IllegalArgumentException("Invalid atlas index: " + index);
        };
    }

    public void dumpAllContents(Path destDir) {
        if (!destDir.toFile().exists()) {
            destDir.toFile().mkdirs();
        }
        for (int i = 0; i < 3; i++) {
            TextureAtlas atlas = getAtlas(i);
            String suffix = switch (i) {
                case 0 -> "texture";
                case 1 -> "normal";
                case 2 -> "specular";
                default -> throw new IllegalArgumentException("Invalid atlas index: " + i);
            };
            Path path = destDir.resolve(suffix);
            if (!path.toFile().exists()) {
                path.toFile().mkdirs();
            }
            try {
                atlas.dumpContents(ResourceLocation.tryParse("texture"), path);
            } catch (Exception e) {
                System.err.println("Failed to dump " + suffix + " atlas: " + e.getMessage());
            }
        }
    }
}
