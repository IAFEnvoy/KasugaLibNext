package test.kasuga.modelling.pipeline;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.rendering.models.mc.Constants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.slf4j.Logger;

@Context()
public class PipelineStatusLogger {
    private final Logger logger = LogUtils.getLogger();

    @Inject() @Named("modEventBus") IEventBus eventBus;

    @PostConstruct()
    public void init() {
        eventBus.addListener(RegisterClientReloadListenersEvent.class, this::onReloadListenerRegister);
    }

    private void onReloadListenerRegister(RegisterClientReloadListenersEvent event) {
        logger.info("=== Modelling Pipeline Status ===");
        logPipeline("BE_PIPELINE", Constants.BE_PIPELINE);
        logPipeline("JE_PIPELINE", Constants.JE_PIPELINE);
        logPipeline("OBJ_PIPELINE", Constants.OBJ_PIPELINE);
        logPipeline("MMD_PIPELINE", Constants.MMD_PIPELINE);
        logBackend("MC_BACKEND", Constants.MC_BACKEND);
        logger.info("================================");
    }

    private void logPipeline(String name, Object pipeline) {
        logger.info("  {}: {}", name, pipeline != null ? "✓ present" : "✗ null");
    }

    private void logBackend(String name, Object backend) {
        logger.info("  {}: {}", name, backend != null ? "✓ present" : "✗ null");
    }
}
