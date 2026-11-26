package ae2opti.mixin;

import ae2opti.AE2Opti;
import ae2opti.AE2OptiConfig;
import ae2opti.CraftingBudgetHolder;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * ACO (AE2 Crafting Optimizer) の「CPU実行の時間予算」の考え方を 1.12.2 向けに書き直したもの。
 *
 * <p>updateCraftingLogic は remainingOperations = accelerator + 1 - (直近3tickの使用数)
 * だけパターンを送る。その accelerator の読み出しだけを差し替える。
 * GUI表示や getCoProcessors() の値は変えない。</p>
 *
 * <ol>
 *   <li>CPUごと: 実測時間に合わせて上限 (cap) を自動調整する。目標時間は
 *       targetMillisPerCpu と「網の予算の持ち分」の小さいほう。</li>
 *   <li>ME網ごと: 網の予算 (sharedMillisPerGrid) を、動いているCPUで均等に割る。
 *       使い切らなかったCPUの余りは、足りなかったCPUに回す（{@link CraftingBudgetHolder#ae2opti$getShareNanos}）。
 *       持ち分は前のtickの集計で決めるので、AE2 がCPUを回す順番に左右されない。</li>
 *   <li>緊急停止: それでも網が予算の {@link #EMERGENCY_FACTOR} 倍を使ったら、残りのCPUは最低限しか送らない。
 *       ただし前のtickに止められたCPUは止めない（同じCPUばかり後回しにならないように）。</li>
 *   <li>送り先のキャッシュ: このtick「埋まっている」と答えた送り先には、同じ網の中で聞き直さない。</li>
 *   <li>材料チェックの省略: パターンの送り先がすべて「埋まっている」と分かっているなら、canCraft を呼ばない。</li>
 * </ol>
 *
 * <p>以前は「網の予算を使い切ったら、残りのCPUは最低限」だった。CPUを回す順番は毎tick同じなので、
 * 後ろのほうのCPUがずっと後回しになる不具合があった（ROADMAP 優先度2）。</p>
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterMixin {

    /** 網の予算の何倍を使ったら、残りのCPUを止めるか */
    @Unique
    private static final long EMERGENCY_FACTOR = 2L;

    @Shadow
    private int accelerator;

    @Shadow
    private int remainingOperations;

    /** 実効コプロセッサ数の上限。-1 = まだ決まっていない（制限なし） */
    @Unique
    private int ae2opti$cap = -1;

    @Unique
    private long ae2opti$startNanos;

    /** このtickの目標時間（ナノ秒）。-1 = 自動調整しない */
    @Unique
    private long ae2opti$targetNanos = -1L;

    /** このtick、cap がこれより下がらない値 */
    @Unique
    private int ae2opti$floor;

    /** このtick、網の予算の緊急停止で止められたか */
    @Unique
    private boolean ae2opti$emergencyCut;

    /** 前のtick、緊急停止で止められたか */
    @Unique
    private boolean ae2opti$cutLastTick;

    /** このtick executeCrafting まで進んだか（タスクが無い・完了済みなら false） */
    @Unique
    private boolean ae2opti$ran;

    /** updateCraftingLogic の間だけ持つ、この網の CraftingGridCache */
    @Unique
    private CraftingGridCache ae2opti$grid;

    @Unique
    private static CraftingBudgetHolder ae2opti$holder(CraftingGridCache cc) {
        return cc instanceof CraftingBudgetHolder ? (CraftingBudgetHolder) cc : null;
    }

    @Inject(method = "updateCraftingLogic", at = @At("HEAD"))
    private void ae2opti$startTimer(IGrid grid, IEnergyGrid eg, CraftingGridCache cc, CallbackInfo ci) {
        this.ae2opti$startNanos = System.nanoTime();
        this.ae2opti$ran = false;
        this.ae2opti$grid = cc;

        final AE2OptiConfig.Crafting cfg = AE2OptiConfig.crafting;
        long target = cfg.throttleCpu ? cfg.targetMillisPerCpu * 1_000_000L : -1L;
        int floor = cfg.minCoProcessors;
        boolean cut = false;

        final CraftingBudgetHolder holder = ae2opti$holder(cc);
        if (cfg.sharedGridBudget && holder != null) {
            final long budget = cfg.sharedMillisPerGrid * 1_000_000L;
            final long share = holder.ae2opti$getShareNanos(budget);
            if (target < 0 || share < target) {
                // 網の持ち分のほうが厳しい: 持ち分を目標にし、下限も「網が混んでいるとき」の値にする
                target = share;
                floor = cfg.minCoProcessorsWhenGridBusy;
            }
            cut = holder.ae2opti$getUsedNanos() >= budget * EMERGENCY_FACTOR && !this.ae2opti$cutLastTick;
        }

        this.ae2opti$targetNanos = target;
        this.ae2opti$floor = floor;
        this.ae2opti$emergencyCut = cut;
    }

    @ModifyExpressionValue(method = "updateCraftingLogic",
            at = @At(value = "FIELD",
                    target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;accelerator:I",
                    opcode = Opcodes.GETFIELD))
    private int ae2opti$limitCoProcessors(int original) {
        int result = original;

        if (this.ae2opti$targetNanos >= 0) {
            final int min = Math.min(original, this.ae2opti$floor);
            if (this.ae2opti$cap < 0 || this.ae2opti$cap > original) {
                this.ae2opti$cap = original;
            } else if (this.ae2opti$cap < min) {
                this.ae2opti$cap = min;
            }
            result = this.ae2opti$cap;
        } else {
            this.ae2opti$cap = -1;
        }

        if (this.ae2opti$emergencyCut) {
            result = Math.min(result, AE2OptiConfig.crafting.minCoProcessorsWhenGridBusy);
        }
        return result;
    }

    @Inject(method = "executeCrafting", at = @At("HEAD"))
    private void ae2opti$markRan(IEnergyGrid eg, CraftingGridCache cc, CallbackInfo ci) {
        this.ae2opti$ran = true;
    }

    /**
     * [A] 「埋まっている」という答えだけを、このtickの間、網ごとに覚える。
     * 「空いている」は覚えない（パターンを送ると埋まるため）。
     */
    @WrapOperation(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingMedium;isBusy()Z"))
    private boolean ae2opti$cacheBusy(ICraftingMedium medium, Operation<Boolean> original) {
        final CraftingBudgetHolder holder = ae2opti$holder(this.ae2opti$grid);
        if (!AE2OptiConfig.crafting.cacheBusyMediums || holder == null || !holder.ae2opti$inUpdateTick()) {
            return original.call(medium);
        }
        if (holder.ae2opti$isKnownBusy(medium)) {
            return true;
        }
        final boolean busy = original.call(medium);
        if (busy) {
            holder.ae2opti$markBusy(medium);
        }
        return busy;
    }

    /**
     * [C] パターンの送り先がすべて「埋まっている」と分かっているなら、材料チェック（canCraft のシミュレート取り出し）をしない。
     *
     * <p>元の処理でも、canCraft が true になったあと送り先が全部埋まっていれば何も送られないので、結果は同じ。
     * 違いは、visitedMediums の順番待ちの列が空になるまで回されないことだけ（次に回る送り先が少し変わる）。</p>
     *
     * <p>AE2Enhanced は canCraft の失敗を「CPUの在庫が変わるまで」覚える（CanCraftCacheService）。
     * こちらが内側で false を返すと材料不足として覚えられ、送り先が空いても止まったままになりうるので、
     * AE2Enhanced があるときは使わない。</p>
     */
    @WrapOperation(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;canCraft(Lappeng/api/networking/crafting/ICraftingPatternDetails;[Lappeng/api/storage/data/IAEItemStack;)Z"))
    private boolean ae2opti$skipCanCraftWhenAllBusy(CraftingCPUCluster self, ICraftingPatternDetails details,
            IAEItemStack[] condensedInputs, Operation<Boolean> original) {
        final AE2OptiConfig.Crafting cfg = AE2OptiConfig.crafting;
        final CraftingBudgetHolder holder = ae2opti$holder(this.ae2opti$grid);
        if (!cfg.skipCraftCheckWhenAllBusy || !cfg.cacheBusyMediums || AE2Opti.ae2EnhancedLoaded
                || holder == null || !holder.ae2opti$inUpdateTick()) {
            return original.call(self, details, condensedInputs);
        }

        // cc.getMediums はパターンのハッシュ計算が重い（spark で canCraft 本体より重かった）ので、同じオブジェクトで引くキャッシュを使う
        final List<ICraftingMedium> mediums = holder.ae2opti$getMediumsCached(details);
        if (mediums.isEmpty()) {
            return original.call(self, details, condensedInputs);
        }
        for (ICraftingMedium m : mediums) {
            if (m != null && !holder.ae2opti$isKnownBusy(m)) {
                return original.call(self, details, condensedInputs);
            }
        }
        // 送り先は全部埋まっている（null は元の処理でも捨てられる）
        return false;
    }

    @Inject(method = "updateCraftingLogic", at = @At("RETURN"))
    private void ae2opti$afterUpdate(IGrid grid, IEnergyGrid eg, CraftingGridCache cc, CallbackInfo ci) {
        final long elapsed = System.nanoTime() - this.ae2opti$startNanos;
        this.ae2opti$grid = null;

        final CraftingBudgetHolder holder = ae2opti$holder(cc);
        if (holder != null) {
            holder.ae2opti$addUsedNanos(elapsed);
            if (this.ae2opti$ran) {
                // 許された回数を使い切った = もっと送りたかった
                holder.ae2opti$recordCpu(this.remainingOperations <= 0, elapsed);
            }
        }

        final boolean cut = this.ae2opti$emergencyCut;
        this.ae2opti$cutLastTick = cut;

        // 止められたtickの時間は参考にならないので、cap は動かさない
        if (cut || this.ae2opti$targetNanos < 0 || this.ae2opti$cap < 0) {
            return;
        }
        final long target = Math.max(1L, this.ae2opti$targetNanos);
        final int max = this.accelerator;
        final int min = Math.min(max, this.ae2opti$floor);
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
