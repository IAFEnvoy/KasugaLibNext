package lib.kasuga.rendering.models.mc.source.model;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lombok.NonNull;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class KasugaModelManager implements PreparableReloadListener, AutoCloseable {

    private final Collection<KasugaTextureManager> textureManagers;
    private final Collection<Object> inputIdentifiers;

    private ModelScanner modelScanner;
    private PipeLineRouter pipeLineRouter;

    public KasugaModelManager(Collection<KasugaTextureManager> textureManagers) {
        this.textureManagers = textureManagers;
        this.inputIdentifiers = new ArrayList<>();

        this.modelScanner = new KasugaModelScanner();
        this.pipeLineRouter = new KasugaPipeLineRouter();
    }

    public KasugaModelManager(SourceType type,
                              String name,
                              Collection<KasugaTextureManager> textureManagers,
                              ModelScanner modelScanner,
                              PipeLineRouter pipeLineRouter) {
        this.inputIdentifiers = new ArrayList<>();
        this.textureManagers = textureManagers;
        this.modelScanner = modelScanner;
        this.pipeLineRouter = pipeLineRouter;
    }

    public void registerModel(Object inputIdentifier) {
        this.inputIdentifiers.add(inputIdentifier);
    }

    public void registerRouter(PipeLineRouter router) {
        this.pipeLineRouter = router;
    }

    public void registerModelScanner(ModelScanner modelScanner) {
        this.modelScanner = modelScanner;
    }

    @Override
    public void close() throws Exception {}

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier,
                                          @NonNull ResourceManager resourceManager,
                                          @NonNull ProfilerFiller preparationProfiler,
                                          @NonNull ProfilerFiller reloadProfiler,
                                          @NonNull Executor backgroundExecutor,
                                          @NonNull Executor gameExecutor) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {

            modelScanner.setConfig(ModelProxyConfigLoader.loadConfig(resourceManager));
            List<ResourceLocation> scanned = modelScanner.scan(resourceManager);

            Map<ModelPipeLine, List<ResourceLocation>> routed = pipeLineRouter.route(scanned);

            for(Map.Entry<ModelPipeLine, List<ResourceLocation>> entry : routed.entrySet()) {
                for(ResourceLocation location : entry.getValue()) {
                    entry.getKey().loadModel(location, null);
                }
            }

        }, backgroundExecutor);
        CompletableFuture<Void> allTexturesFuture = future.thenCompose(ignored -> {
            List<CompletableFuture<Void>> textureFutures = textureManagers.stream()
                    .map(manager -> manager.reload(barrier, resourceManager, preparationProfiler, reloadProfiler, backgroundExecutor, gameExecutor))
                    .toList();
            return CompletableFuture.allOf(textureFutures.toArray(new CompletableFuture[0]));
        });
        return allTexturesFuture;
    }

    private void loadAllModels(ModelPipeLine pipe) {
        for (Object inputIdentifier : this.inputIdentifiers) {
            pipe.loadModel(inputIdentifier, null);
        }
    }
}
