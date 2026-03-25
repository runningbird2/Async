package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.spawn.AsyncMobcapTrackedMob;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Mob.class)
public class MobMixin implements AsyncMobcapTrackedMob {

    @Unique
    private static final String async$SPAWN_CAP_NBT_KEY = "AsyncCountsTowardSpawnCap";

    @Unique
    private static final Object async$lock = new Object();

    @Unique
    private boolean async$countsTowardSpawnCap;

    @WrapMethod(method = "equipItemIfPossible")
    private ItemStack tryEquip(ServerLevel level, ItemStack stack, Operation<ItemStack> original) {
        synchronized (async$lock) {
            return original.call(level, stack);
        }
    }

    @WrapMethod(method = "pickUpItem")
    private void pickUpItem(ServerLevel level, ItemEntity entity, Operation<Void> original) {
        synchronized (async$lock) {
            original.call(level, entity);
        }
    }

    @WrapMethod(method = "setItemSlotAndDropWhenKilled")
    private void equipLootStack(EquipmentSlot slot, ItemStack stack, Operation<Void> original) {
        synchronized (async$lock) {
            original.call(slot, stack);
        }
    }

    @WrapMethod(method = "setBodyArmorItem")
    private void equipLootStack(ItemStack stack, Operation<Void> original) {
        synchronized (async$lock) {
            original.call(stack);
        }
    }

    @WrapMethod(method = "addAdditionalSaveData")
    private void async$addAdditionalSaveData(ValueOutput output, Operation<Void> original) {
        original.call(output);
        output.putBoolean(async$SPAWN_CAP_NBT_KEY, this.async$countsTowardSpawnCap);
    }

    @WrapMethod(method = "readAdditionalSaveData")
    private void async$readAdditionalSaveData(ValueInput input, Operation<Void> original) {
        original.call(input);
        this.async$countsTowardSpawnCap = input.getBooleanOr(async$SPAWN_CAP_NBT_KEY, false);
    }

    @Override
    public boolean async$countsTowardSpawnCap() {
        return this.async$countsTowardSpawnCap;
    }

    @Override
    public void async$setCountsTowardSpawnCap(boolean countsTowardSpawnCap) {
        this.async$countsTowardSpawnCap = countsTowardSpawnCap;
    }
}
