package ae2opti.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.p2p.FEP2PDistributor;
import appeng.me.cache.helpers.TunnelCollection;
import appeng.parts.p2p.PartP2PFEPower;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P2P FE の入口（{@code PartP2PFEPower$InputEnergyStorage}）への mixin。
 *
 * <h3>新しい配分処理（{@code p2p.newDistribution}、デフォルトでオン）</h3>
 * <p>{@code receiveEnergy} / {@code getEnergyStored} / {@code getMaxEnergyStored} を、先頭で
 * {@link FEP2PDistributor} に置き換える（long での配分、[P-3] [P-5] [P-7] [P-8] などはそちらを参照）。
 * {@code int} の FE の口から入る量は {@code int} までなので、元と同じ量を同じ順で配る。</p>
 *
 * <h3>[P-2] 元の配分処理のまま、空の2周目を飛ばす（{@code p2p.newDistribution} がオフのとき）</h3>
 * <p>1周目の {@code output.receiveEnergy(...)}（ordinal 0）の戻り値を見て、1回でも 0 以外があったら覚えておく
 * （{@link Share} なので呼び出しごとに別）。2周目の出口一覧を取る {@code getOutputs()}（ordinal 2。0 = size() 用、
 * 1 = 1周目、2 = 2周目）を、何も受け取られなかったときだけ空の一覧にすり替える。
 * 設定 {@code p2p.skipEmptySecondPass}（デフォルトでオン。新しい配分処理でも同じ設定を見る）。</p>
 */
@Mixin(targets = "appeng.parts.p2p.PartP2PFEPower$InputEnergyStorage", remap = false)
public abstract class PartP2PFEPowerInputMixin {

    // 要ローカル検証: 内部クラスから外側のインスタンスへの合成フィールド名が this$0 であること
    @Shadow
    @Final
    PartP2PFEPower this$0;

    // ---- 新しい配分処理 ----

    @Inject(method = "receiveEnergy", at = @At("HEAD"), cancellable = true)
    private void ae2opti$distribute(int maxReceive, boolean simulate, CallbackInfoReturnable<Integer> cir) {
        if (AE2OptiConfig.p2p.newDistribution) {
            cir.setReturnValue((int) Math.min(Integer.MAX_VALUE, FEP2PDistributor.distribute(this.this$0, maxReceive, simulate)));
        }
    }

    @Inject(method = "getEnergyStored", at = @At("HEAD"), cancellable = true)
    private void ae2opti$energyStored(CallbackInfoReturnable<Integer> cir) {
        if (AE2OptiConfig.p2p.newDistribution) {
            cir.setReturnValue(FEP2PDistributor.sumEnergyStored(this.this$0, false));
        }
    }

    @Inject(method = "getMaxEnergyStored", at = @At("HEAD"), cancellable = true)
    private void ae2opti$maxEnergyStored(CallbackInfoReturnable<Integer> cir) {
        if (AE2OptiConfig.p2p.newDistribution) {
            cir.setReturnValue(FEP2PDistributor.sumEnergyStored(this.this$0, true));
        }
    }

    // ---- [P-2]（元の配分処理のとき） ----

    // 要ローカル検証: 1周目の receiveEnergy が ordinal 0 であること（v0.56.6 / v0.56.7 のソースでは 0 = 1周目、1 = 2周目）
    @ModifyExpressionValue(method = "receiveEnergy",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/energy/IEnergyStorage;receiveEnergy(IZ)I",
                    ordinal = 0))
    private int ae2opti$trackFirstPass(int received, @Share("ae2opti$anyReceived") LocalBooleanRef anyReceived) {
        if (received != 0) {
            anyReceived.set(true);
        }
        return received;
    }

    // 要ローカル検証: 2周目の getOutputs() が ordinal 2 で、呼び出し先のクラスが PartP2PFEPower になっていること
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "receiveEnergy",
            at = @At(value = "INVOKE",
                    target = "Lappeng/parts/p2p/PartP2PFEPower;getOutputs()Lappeng/me/cache/helpers/TunnelCollection;",
                    ordinal = 2))
    private TunnelCollection ae2opti$skipEmptySecondPass(PartP2PFEPower part, Operation<TunnelCollection> original,
                                                         @Share("ae2opti$anyReceived") LocalBooleanRef anyReceived) {
        if (AE2OptiConfig.p2p.skipEmptySecondPass && !anyReceived.get()) {
            // tunnelSources が null の TunnelCollection は、何も返さない iterator を作る
            return new TunnelCollection(null, PartP2PFEPower.class);
        }
        return original.call(part);
    }
}
