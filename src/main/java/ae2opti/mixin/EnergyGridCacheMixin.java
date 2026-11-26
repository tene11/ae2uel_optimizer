package ae2opti.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.networking.energy.IEnergyGridProvider;
import appeng.me.cache.EnergyGridCache;
import ae2opti.AE2OptiConfig;
import com.google.common.collect.Multiset;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * (1) extractAEPower / injectPower の近道:
 * つながった別の電力網 (energyGridProviders) が無いときは、PriorityQueue と HashSet を作って
 * 探索する必要がないので、自分の extractProviderPower / injectProviderPower を直接呼ぶ。
 * AE2UEL v0.56.6 の元の処理で、別の電力網が無い場合とまったく同じ結果になる。
 *
 * <p>injectPower はさらに、石英ファイバーなどで別の電力網とつながっている場合も近道する。
 * 元の処理では最初にキューに入るのは自分だけなので、一番最初に電力を受け取るのは必ず自分になる。
 * そこで先に自分の injectProviderPower を呼び、全部受け取れた（余りが 0）ならそこで終わる。
 * 余りが出たときだけ、元と同じ順番（COMPARATOR_LOWEST_PERCENTAGE_FIRST）でつながった電力網を探索する。
 * 元の処理と違うのは「全部受け取れたあとに、つながった電力網をキューへ入れる（比較のために
 * refreshPower が走る）」部分を省くことだけで、refreshPower は読む前に毎回やり直されるので結果は変わらない。</p>
 *
 * <p>extractAEPower と getEnergyDemand も同じ考え方で近道する（1-A）。
 * 元の処理で最初に poll されるのは必ず自分なので、先に自分の extractProviderPower / getProviderEnergyDemand を呼び、
 * 足りた（取り出し量・需要が目標に届いた）ならそこで終わる。足りないときだけ、元と同じ順番
 * （取り出しは COMPARATOR_HIGHEST_AMOUNT_STORED_FIRST、需要は COMPARATOR_LOWEST_PERCENTAGE_FIRST）で探索する。
 * 省くのは「足りたあとに、つながった電力網をキューへ入れる（比較のために getProviderStoredEnergy が呼ばれる）」部分だけ。
 * AE2UEL で IEnergyGridProvider を実装しているのは石英ファイバーだけで、その getProviderStoredEnergy は 0 を返すだけなので結果は変わらない。
 * spark では extractAEPower の 2.2% がこのキュー追加（PriorityQueue.add）だった。</p>
 *
 * (2) extractProviderPower / injectProviderPower は呼ばれるたびに
 * providers.removeIf(providerToRemove::contains) で全ストレージを走査する。
 * 削除予定が空なら結果は変わらないので、走査ごと飛ばす。
 */
@Mixin(value = EnergyGridCache.class, remap = false)
public abstract class EnergyGridCacheMixin {

    @Shadow
    @Final
    private Set<IAEPowerStorage> providerToRemove;

    @Shadow
    @Final
    private Set<IAEPowerStorage> requesterToRemove;

    @Shadow
    @Final
    private Multiset<IEnergyGridProvider> energyGridProviders;

    @Shadow
    @Final
    private static Comparator<IEnergyGridProvider> COMPARATOR_LOWEST_PERCENTAGE_FIRST;

    @Shadow
    @Final
    private static Comparator<IEnergyGridProvider> COMPARATOR_HIGHEST_AMOUNT_STORED_FIRST;

    @Inject(method = "extractAEPower", at = @At("HEAD"), cancellable = true)
    private void ae2opti$extractFastPath(double amt, Actionable mode, PowerMultiplier pm,
                                            CallbackInfoReturnable<Double> cir) {
        if (!AE2OptiConfig.energy.fastPath) {
            return;
        }
        final EnergyGridCache self = (EnergyGridCache) (Object) this;
        final double toExtract = pm.multiply(amt);

        // 元の while 条件 (extracted < toExtract、最初は extracted = 0) と同じ。0 以下や NaN なら何も取り出さない。
        double extracted = 0;
        if (!(extracted < toExtract)) {
            cir.setReturnValue(pm.divide(extracted));
            return;
        }

        // 元の処理でも最初に poll されるのは必ず自分なので、先に自分から取り出す。
        extracted += self.extractProviderPower(toExtract - extracted, mode);
        if (!(extracted < toExtract) || this.energyGridProviders.isEmpty()) {
            cir.setReturnValue(pm.divide(extracted));
            return;
        }

        // 足りなかったときだけ、元の処理の続き（自分を訪問済みにした状態から）を同じ手順で行う。
        final Queue<IEnergyGridProvider> toVisit = new PriorityQueue<>(COMPARATOR_HIGHEST_AMOUNT_STORED_FIRST);
        final Set<IEnergyGridProvider> visited = new HashSet<>();
        visited.add(self);
        for (IEnergyGridProvider provider : this.energyGridProviders) {
            if (!visited.contains(provider)) {
                toVisit.add(provider);
            }
        }

        while (!toVisit.isEmpty() && extracted < toExtract) {
            final IEnergyGridProvider next = toVisit.poll();
            visited.add(next);
            extracted += next.extractProviderPower(toExtract - extracted, mode);
            for (IEnergyGridProvider provider : next.providers()) {
                if (!visited.contains(provider)) {
                    toVisit.add(provider);
                }
            }
        }

        cir.setReturnValue(pm.divide(extracted));
    }

    @Inject(method = "getEnergyDemand", at = @At("HEAD"), cancellable = true)
    private void ae2opti$demandFastPath(double maxRequired, CallbackInfoReturnable<Double> cir) {
        if (!AE2OptiConfig.energy.fastPath) {
            return;
        }
        final EnergyGridCache self = (EnergyGridCache) (Object) this;

        // 元の while 条件 (required < maxRequired、最初は required = 0) と同じ。
        double required = 0;
        if (!(required < maxRequired)) {
            cir.setReturnValue(required);
            return;
        }

        // 元の処理でも最初に poll されるのは必ず自分なので、先に自分の需要を聞く。
        required += self.getProviderEnergyDemand(maxRequired - required);
        if (!(required < maxRequired) || this.energyGridProviders.isEmpty()) {
            cir.setReturnValue(required);
            return;
        }

        final Queue<IEnergyGridProvider> toVisit = new PriorityQueue<>(COMPARATOR_LOWEST_PERCENTAGE_FIRST);
        final Set<IEnergyGridProvider> visited = new HashSet<>();
        visited.add(self);
        for (IEnergyGridProvider provider : this.energyGridProviders) {
            if (!visited.contains(provider)) {
                toVisit.add(provider);
            }
        }

        while (!toVisit.isEmpty() && required < maxRequired) {
            final IEnergyGridProvider next = toVisit.poll();
            visited.add(next);
            required += next.getProviderEnergyDemand(maxRequired - required);
            for (IEnergyGridProvider provider : next.providers()) {
                if (!visited.contains(provider)) {
                    toVisit.add(provider);
                }
            }
        }

        cir.setReturnValue(required);
    }

    @Inject(method = "injectPower", at = @At("HEAD"), cancellable = true)
    private void ae2opti$injectFastPath(double amt, Actionable mode, CallbackInfoReturnable<Double> cir) {
        if (!AE2OptiConfig.energy.fastPath) {
            return;
        }
        final EnergyGridCache self = (EnergyGridCache) (Object) this;

        // 元の while 条件 (leftover > 0) と同じ。0 以下や NaN ならそのまま返す。
        double leftover = amt;
        if (!(leftover > 0)) {
            cir.setReturnValue(leftover);
            return;
        }

        // 元の処理でも最初に poll されるのは必ず自分なので、先に自分へ入れる。
        leftover = self.injectProviderPower(leftover, mode);
        if (!(leftover > 0) || this.energyGridProviders.isEmpty()) {
            cir.setReturnValue(leftover);
            return;
        }

        // 余りが出たときだけ、元の処理の続き（自分を訪問済みにした状態から）を同じ手順で行う。
        final Queue<IEnergyGridProvider> toVisit = new PriorityQueue<>(COMPARATOR_LOWEST_PERCENTAGE_FIRST);
        final Set<IEnergyGridProvider> visited = new HashSet<>();
        visited.add(self);
        for (IEnergyGridProvider provider : this.energyGridProviders) {
            if (!visited.contains(provider)) {
                toVisit.add(provider);
            }
        }

        while (!toVisit.isEmpty() && leftover > 0) {
            final IEnergyGridProvider next = toVisit.poll();
            visited.add(next);
            leftover = next.injectProviderPower(leftover, mode);
            for (IEnergyGridProvider provider : next.providers()) {
                if (!visited.contains(provider)) {
                    toVisit.add(provider);
                }
            }
        }

        cir.setReturnValue(leftover);
    }

    @WrapOperation(method = "extractProviderPower",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;removeIf(Ljava/util/function/Predicate;)Z"))
    private boolean ae2opti$skipEmptyProviderRemoval(Set<IAEPowerStorage> set, Predicate<? super IAEPowerStorage> filter,
                                                        Operation<Boolean> original) {
        if (AE2OptiConfig.energy.skipEmptyRemoval && this.providerToRemove.isEmpty()) {
            return false;
        }
        return original.call(set, filter);
    }

    @WrapOperation(method = "injectProviderPower",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;removeIf(Ljava/util/function/Predicate;)Z"))
    private boolean ae2opti$skipEmptyRequesterRemoval(Set<IAEPowerStorage> set, Predicate<? super IAEPowerStorage> filter,
                                                         Operation<Boolean> original) {
        if (AE2OptiConfig.energy.skipEmptyRemoval && this.requesterToRemove.isEmpty()) {
            return false;
        }
        return original.call(set, filter);
    }
}
