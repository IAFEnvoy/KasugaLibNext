package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import net.minecraft.resources.ResourceLocation;

/**
 * Identity of a synchronized machine: definition id + dimension + host discriminator, derived
 * identically on both ends so a per-key version counter deduplicates pushes. The dimension is part
 * of the key — same-id machines in different dimensions never collide.
 *
 * @param machineId           the {@code ResourceLocation} of the machine's definition
 * @param dimension           the machine's dimension ({@code level.dimension().location()})
 * @param ownerDiscriminator  host-provided discriminator (block entity = {@code worldPosition.asLong()},
 *                            entity = entity id, global = 0)
 */
public record FsmSyncKey(ResourceLocation machineId, ResourceLocation dimension, long ownerDiscriminator) {
}
