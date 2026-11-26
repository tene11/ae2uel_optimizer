package ae2opti.mixin;

import ae2opti.CraftingBudgetHolder;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.me.cache.CraftingGridCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * onUpdateTick は1tickに1回、この網のクラフトCPUを全部 updateCraftingLogic する。
 *
 * <p>その頭で次のことをする。</p>
 * <ul>
 *   <li>使用時間をリセットする（各CPUが使った時間をこのあと足していく）</li>
 *   <li>前のtickの集計（動いたCPUの数・足りなかったCPUの数・足りたCPUが使った時間）を保存し、今のtickの集計を0にする</li>
 *   <li>「埋まっている」と分かった送り先の一覧を空にする</li>
 * </ul>
 *
 * <p>CPUを回す順番（HashSet の順）には手を付けない。順番に左右されないように、持ち時間は前のtickの集計から決める。</p>
 *
 * <p>パターン → 送り先の一覧は、同じオブジェクトかどうかで引けるようにキャッシュする（{@link #ae2opti$getMediumsCached}）。
 * 元の一覧（craftingMethods）は recalculateCraftingPatterns でしか作り直されないので、その頭と終わりで空にする。</p>
 */
@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class CraftingGridCacheMixin implements CraftingBudgetHolder {

    @Unique
    private long ae2opti$usedNanos;

    // 今のtickの集計
    @Unique
    private int ae2opti$activeNow;
    @Unique
    private int ae2opti$hungryNow;
    @Unique
    private long ae2opti$satisfiedNanosNow;

    // 前のtickの集計
    @Unique
    private int ae2opti$activeLast;
    @Unique
    private int ae2opti$hungryLast;
    @Unique
    private long ae2opti$satisfiedNanosLast;

    /** このtick「埋まっている」と答えた送り先。equals ではなく同じオブジェクトかどうかで見る */
    @Unique
    private final Set<Object> ae2opti$busyMediums = Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private boolean ae2opti$inTick;

    /** パターン（同じオブジェクト） → 送り先の一覧。recalculateCraftingPatterns で空にする */
    @Unique
    private final Map<ICraftingPatternDetails, List<ICraftingMedium>> ae2opti$mediumsByIdentity = new IdentityHashMap<>();

    /** 終わったジョブのパターンが残り続けないように、これを超えたら一度空にする */
    @Unique
    private static final int AE2OPTI$MEDIUM_CACHE_LIMIT = 4096;

    @Shadow
    public abstract List<ICraftingMedium> getMediums(ICraftingPatternDetails key);

    @Inject(method = "onUpdateTick", at = @At("HEAD"))
    private void ae2opti$beginTick(CallbackInfo ci) {
        this.ae2opti$usedNanos = 0L;

        this.ae2opti$activeLast = this.ae2opti$activeNow;
        this.ae2opti$hungryLast = this.ae2opti$hungryNow;
        this.ae2opti$satisfiedNanosLast = this.ae2opti$satisfiedNanosNow;
        this.ae2opti$activeNow = 0;
        this.ae2opti$hungryNow = 0;
        this.ae2opti$satisfiedNanosNow = 0L;

        this.ae2opti$busyMediums.clear();
        this.ae2opti$inTick = true;
    }

    @Inject(method = "onUpdateTick", at = @At("RETURN"))
    private void ae2opti$endTick(CallbackInfo ci) {
        this.ae2opti$inTick = false;
        // 送り先のオブジェクトをtickをまたいで持ち続けない
        this.ae2opti$busyMediums.clear();
    }

    @Inject(method = "recalculateCraftingPatterns", at = @At("HEAD"))
    private void ae2opti$clearMediumsBefore(CallbackInfo ci) {
        this.ae2opti$mediumsByIdentity.clear();
    }

    @Inject(method = "recalculateCraftingPatterns", at = @At("RETURN"))
    private void ae2opti$clearMediumsAfter(CallbackInfo ci) {
        this.ae2opti$mediumsByIdentity.clear();
    }

    @Override
    public List<ICraftingMedium> ae2opti$getMediumsCached(ICraftingPatternDetails details) {
        List<ICraftingMedium> mediums = this.ae2opti$mediumsByIdentity.get(details);
        if (mediums == null) {
            if (this.ae2opti$mediumsByIdentity.size() >= AE2OPTI$MEDIUM_CACHE_LIMIT) {
                this.ae2opti$mediumsByIdentity.clear();
            }
            mediums = this.getMediums(details);
            this.ae2opti$mediumsByIdentity.put(details, mediums);
        }
        return mediums;
    }

    @Override
    public long ae2opti$getUsedNanos() {
        return this.ae2opti$usedNanos;
    }

    @Override
    public void ae2opti$addUsedNanos(long nanos) {
        this.ae2opti$usedNanos += nanos;
    }

    @Override
    public void ae2opti$recordCpu(boolean hungry, long nanos) {
        this.ae2opti$activeNow++;
        if (hungry) {
            this.ae2opti$hungryNow++;
        } else {
            this.ae2opti$satisfiedNanosNow += nanos;
        }
    }

    @Override
    public long ae2opti$getShareNanos(long budgetNanos) {
        final int active = this.ae2opti$activeLast;
        if (active <= 1) {
            return budgetNanos;
        }
        // 最低でも均等割りの分はもらえる
        final long equal = budgetNanos / active;
        final int hungry = this.ae2opti$hungryLast;
        if (hungry <= 0) {
            return equal;
        }
        // 足りたCPUが使わなかった分を、足りなかったCPUで分ける
        final long leftover = (budgetNanos - this.ae2opti$satisfiedNanosLast) / hungry;
        return Math.max(equal, leftover);
    }

    @Override
    public boolean ae2opti$inUpdateTick() {
        return this.ae2opti$inTick;
    }

    @Override
    public boolean ae2opti$isKnownBusy(Object medium) {
        return this.ae2opti$busyMediums.contains(medium);
    }

    @Override
    public void ae2opti$markBusy(Object medium) {
        this.ae2opti$busyMediums.add(medium);
    }
}
