package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.effect.post.FullscreenPassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * Owns the weighted-blended OIT target and the two geometry subpasses used to
 * fill it. The ordinary sorted translucent path remains the caller's fallback.
 */
final class OitRenderer implements AutoCloseable {

    static final int NORMAL = 0;
    static final int ACCUMULATION = 1;
    static final int REVEALAGE = 2;

    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean disabled;
    private OitTarget target;

    /**
     * @return true when the translucent pass was handled by OIT. False tells
     * the backend to use its conventional sorted fallback.
     */
    boolean render(MCBackend backend, MCBackendContext context) {
        if (!canUse()) return false;

        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        ShaderInstance composite = RenderState.OIT_COMPOSITE_SHADER_INSTANCE;
        if (!mainTarget.useDepth || mainTarget.frameBufferId < 0
                || mainTarget.width <= 0 || mainTarget.height <= 0
                || composite == null) {
            return false;
        }

        RenderSystem.assertOnRenderThread();
        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        boolean resolveAttempted = false;
        try {
            if (target == null) target = new OitTarget();
            target.prepare(mainTarget);

            // The target owns a copy of the scene depth. Both geometry passes
            // test it, but their COLOR_WRITE-only states leave that depth
            // unchanged so translucent fragments never hide one another.
            target.bindAccumulation();
            backend.renderObjectsInternal(context, ModelRenderPass.TRANSLUCENT,
                    RenderState.OIT_ACCUMULATION_RENDER_TYPE, ACCUMULATION, false);

            target.bindRevealage();
            backend.renderObjectsInternal(context, ModelRenderPass.TRANSLUCENT,
                    RenderState.OIT_REVEALAGE_RENDER_TYPE, REVEALAGE, false);

            // The resolve samples only the two OIT textures and writes to the
            // main target. It therefore never reads a color attachment while
            // writing that same attachment.
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            resolveAttempted = true;
            OitTarget resolvedTarget = target;
            FullscreenPassRenderer.draw(mainTarget, composite, false, shader -> {
                shader.setSampler("AccumulationSampler", resolvedTarget.accumulationTextureId());
                shader.setSampler("RevealageSampler", resolvedTarget.revealageTextureId());
            });
            return true;
        } catch (RuntimeException exception) {
            disabled = true;
            if (target != null) {
                try {
                    target.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                target = null;
            }
            LOGGER.warn("Disabling Kasuga weighted OIT after a rendering failure; "
                    + "using sorted translucent fallback", exception);
            // If the resolve was already entered, the main target may have
            // been touched. Do not draw the fallback over a partial resolve.
            return resolveAttempted;
        } finally {
            // FullscreenPassRenderer restores the state it saw on entry (the
            // OIT target), so explicitly put the Minecraft target back before
            // restoring the outer snapshot as well.
            mainTarget.bindWrite(true);
            RenderSystem.restoreGlState(backup);
            mainTarget.bindWrite(true);
        }
    }

    private boolean canUse() {
        if (disabled || BackendInstance.isIrisEnabled()
                || RenderState.OIT_ACCUMULATION_RENDER_TYPE == null
                || RenderState.OIT_REVEALAGE_RENDER_TYPE == null
                || RenderState.OIT_COMPOSITE_SHADER_INSTANCE == null) {
            return false;
        }
        try {
            if (!GL.getCapabilities().OpenGL30) return false;
            return GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS) >= 2
                    && GL11.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS) >= 2;
        } catch (RuntimeException ignored) {
            // A client without a current GL context or without the required
            // capability fails closed to the already-proven sorted path.
            return false;
        }
    }

    @Override
    public void close() {
        if (target != null) {
            target.close();
            target = null;
        }
    }
}
