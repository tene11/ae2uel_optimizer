package ae2opti.mixin;

import ae2opti.CraftingBudgetHolder;
import appeng.me.cache.CraftingGridCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * onUpdateTick は1tickに1回、この網のクラフトCPUを全部 updateCraftingLogic する。
 * その頭で使用時間をリセットし、各CPUが使った時間を足していく。
 */
@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class CraftingGridCacheMixin implements CraftingBudgetHolder {

    @Unique
    private long ae2opti$usedNanos;

    @Inject(method = "onUpdateTick", at = @At("HEAD"))
    private void ae2opti$resetBudget(CallbackInfo ci) {
        this.ae2opti$usedNanos = 0L;
    }

    @Override
    public long ae2opti$getUsedNanos() {
        return this.ae2opti$usedNanos;
    }

    @Override
    public void ae2opti$addUsedNanos(long nanos) {
        this.ae2opti$usedNanos += nanos;
    }
}
