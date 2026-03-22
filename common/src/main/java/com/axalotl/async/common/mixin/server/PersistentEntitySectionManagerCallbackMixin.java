package com.axalotl.async.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PersistentEntitySectionManager.Callback.class)
public abstract class PersistentEntitySectionManagerCallbackMixin {

    @Unique
    private volatile boolean async$removed;

    @Unique
    private PersistentEntitySectionManager<?> async$outerManager;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void async$captureOuter(PersistentEntitySectionManager<?> outer, EntityAccess entity, long sectionKey, EntitySection<?> section, CallbackInfo ci) {
        this.async$outerManager = outer;
    }

    @WrapMethod(method = "onMove")
    private void async$serializeOnMove(Operation<Void> original) {
        Object monitor = this.async$outerManager != null ? this.async$outerManager : this;
        synchronized (monitor) {
            if (this.async$removed) {
                return;
            }
            original.call();
        }
    }

    @WrapMethod(method = "onRemove")
    private void async$serializeOnRemove(Entity.RemovalReason reason, Operation<Void> original) {
        Object monitor = this.async$outerManager != null ? this.async$outerManager : this;
        synchronized (monitor) {
            if (this.async$removed) {
                return;
            }
            this.async$removed = true;
            original.call(reason);
        }
    }
}
