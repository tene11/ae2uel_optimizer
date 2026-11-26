package ae2opti.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.CraftingBudgetHolder;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ACO (AE2 Crafting Optimizer) の「CPU実行の時間予算」の考え方を 1.12.2 向けに書き直したもの。
 *
 * updateCraftingLogic は remainingOperations = accelerator + 1 - (直近3tickの使用数)
 * だけパターンを送る。その accelerator の読み出しだけを差し替える。
 * GUI表示や getCoProcessors() の値は変えない。
 *
 * 1. CPUごと: 実測時間に合わせて上限 (cap) を自動調整する。
 * 2. ME網ごと: 同じ網のCPUが、このtickに使った合計時間が予算を超えたら、
 *    残りのCPUは最低限 (minCoProcessorsWhenGridBusy) しか送らない。
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterMixin {

    @Shadow
    private int accelerator;

    /** 実効コプロセッサ数の上限。-1 = まだ決まっていない（制限なし） */
    @Unique
    private int ae2opti$cap = -1;

    @Unique
    private long ae2opti$startNanos;

    /** このtick、同じME網の予算をもう使い切っていたか */
    @Unique
    private boolean ae2opti$gridBusy;

    @Inject(method = "updateCraftingLogic", at = @At("HEAD"))
    private void ae2opti$startTimer(IGrid grid, IEnergyGrid eg, CraftingGridCache cc, CallbackInfo ci) {
        this.ae2opti$startNanos = System.nanoTime();
        this.ae2opti$gridBusy = AE2OptiConfig.crafting.sharedGridBudget
                && cc instanceof CraftingBudgetHolder
                && ((CraftingBudgetHolder) cc).ae2opti$getUsedNanos()
                        >= AE2OptiConfig.crafting.sharedMillisPerGrid * 1_000_000L;
    }

    @ModifyExpressionValue(method = "updateCraftingLogic",
            at = @At(value = "FIELD",
                    target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;accelerator:I",
                    opcode = Opcodes.GETFIELD))
    private int ae2opti$limitCoProcessors(int original) {
        int result = original;

        if (AE2OptiConfig.crafting.throttleCpu) {
            int min = Math.min(original, AE2OptiConfig.crafting.minCoProcessors);
            if (this.ae2opti$cap < 0 || this.ae2opti$cap > original) {
                this.ae2opti$cap = original;
            } else if (this.ae2opti$cap < min) {
                this.ae2opti$cap = min;
            }
            result = this.ae2opti$cap;
        } else {
            this.ae2opti$cap = -1;
        }

        if (this.ae2opti$gridBusy) {
            result = Math.min(result, AE2OptiConfig.crafting.minCoProcessorsWhenGridBusy);
        }
        return result;
    }

    @Inject(method = "updateCraftingLogic", at = @At("RETURN"))
    private void ae2opti$afterUpdate(IGrid grid, IEnergyGrid eg, CraftingGridCache cc, CallbackInfo ci) {
        long elapsed = System.nanoTime() - this.ae2opti$startNanos;

        if (cc instanceof CraftingBudgetHolder) {
            ((CraftingBudgetHolder) cc).ae2opti$addUsedNanos(elapsed);
        }

        if (!AE2OptiConfig.crafting.throttleCpu || this.ae2opti$cap < 0) {
            return;
        }
        long target = AE2OptiConfig.crafting.targetMillisPerCpu * 1_000_000L;
        int max = this.accelerator;
        int min = Math.min(max, AE2OptiConfig.crafting.minCoProcessors);
        int cap = this.ae2opti$cap;

        if (elapsed > target) {
            // 目標を超えた: 比例で縮める（最低でも1/4は減らす）
            long proportional = (long) cap * target / elapsed;
            long fallback = cap - Math.max(1, cap / 4);
            cap = (int) Math.max(min, Math.min(proportional, fallback));
        } else if (elapsed < target / 2 && cap < max) {
            // 余裕がある: 1/8ずつ戻す
            cap = (int) Math.min(max, (long) cap + Math.max(1, cap / 8));
        }
        this.ae2opti$cap = cap;
    }
}
