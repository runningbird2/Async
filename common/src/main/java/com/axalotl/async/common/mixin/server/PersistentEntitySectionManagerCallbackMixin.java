package com.axalotl.async.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PersistentEntitySectionManager.Callback.class)
public abstract class PersistentEntitySectionManagerCallbackMixin {

    @Unique
    private volatile boolean async$removed;

    @WrapMethod(method = "onMove")
    private void async$serializeOnMove(Operation<Void> original) {
        synchronized (this) {
            if (this.async$removed) {
                return;
            }
            original.call();
        }
    }

    @WrapMethod(method = "onRemove")
    private void async$serializeOnRemove(Entity.RemovalReason reason, Operation<Void> original) {
        synchronized (this) {
            if (this.async$removed) {
                return;
            }
            this.async$removed = true;
            original.call(reason);
        }
    }
}
