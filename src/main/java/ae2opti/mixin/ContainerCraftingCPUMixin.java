package ae2opti.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.ServerLoad;
import appeng.api.storage.data.IItemList;
import appeng.container.implementations.ContainerCraftingCPU;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * クラフトCPUの状況画面（ContainerCraftingCPU と、それを継承するクラフト状況の画面 ContainerCraftingStatus）の更新間隔を、
 * 重さに合わせて変える。
 *
 * <p>AE2 は画面を開いている間、変わったアイテムを毎tick、GZIP で圧縮した3つのパケットにして送る。
 * 大量クラフト中は spark で 1.03% あった（2026-11-26）。</p>
 *
 * <ul>
 *   <li>普段は元どおり毎tick送る（リアルタイムに動いて見えるように）。</li>
 *   <li>1回の送信が重い（1tickあたりに直して cpuGuiSlowSendMicros 以上）か、
 *       サーバーの平均 MSPT が cpuGuiSlowMspt 以上なら、次は cpuGuiSlowInterval tick 後まで待つ。</li>
 *   <li>待っている間は {@code list.isEmpty()} を true に見せるだけ。変わったアイテムの一覧（list）は
 *       リセットされずにたまっていき、送るときにまとめて最新の数を送るので、取りこぼしはない。</li>
 * </ul>
 *
 * <p>予想時間など @GuiSync の値（super.detectAndSendChanges）は、今までどおり毎tick送る。</p>
 *
 * <p>メソッド名は開発環境（MCP 名）と本番（SRG 名）の両方を書いておく（どちらか片方に当たればよい）。
 * AE2Enhanced（MixinContainerCraftingCPUThrottle）も同じ {@code list.isEmpty()} を包んでいるが、
 * どちらも @WrapOperation なので重ねがけで動く。CrazyAE はクラフト状況の画面を @Overwrite しているが、
 * 最後に super.detectAndSendChanges() を呼ぶので、こちらの処理は通る。</p>
 */
@Mixin(value = ContainerCraftingCPU.class, remap = false)
public abstract class ContainerCraftingCPUMixin {

    /** 送信を何回見送るか（残り） */
    @Unique
    private int ae2opti$wait;

    /** 前に送ってから何tick経ったか */
    @Unique
    private int ae2opti$ticksSinceSend = 1;

    /** このtickは送っているか */
    @Unique
    private boolean ae2opti$sending;

    @Unique
    private long ae2opti$startNanos;

    @Inject(method = {"detectAndSendChanges", "func_75142_b"}, at = @At("HEAD"))
    private void ae2opti$guiHead(CallbackInfo ci) {
        this.ae2opti$sending = false;
        this.ae2opti$startNanos = System.nanoTime();
    }

    @WrapOperation(method = {"detectAndSendChanges", "func_75142_b"},
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/data/IItemList;isEmpty()Z"))
    private boolean ae2opti$throttleGui(IItemList<?> list, Operation<Boolean> original) {
        final boolean empty = original.call(list);
        if (empty || !AE2OptiConfig.crafting.cpuGuiAdaptiveUpdate) {
            return empty;
        }
        if (this.ae2opti$wait > 0) {
            this.ae2opti$wait--;
            // 送らない（一覧はそのまま残る）
            return true;
        }
        this.ae2opti$sending = true;
        return false;
    }

    @Inject(method = {"detectAndSendChanges", "func_75142_b"}, at = @At("RETURN"))
    private void ae2opti$guiReturn(CallbackInfo ci) {
        final AE2OptiConfig.Crafting cfg = AE2OptiConfig.crafting;
        if (!this.ae2opti$sending) {
            if (this.ae2opti$ticksSinceSend < Integer.MAX_VALUE) {
                this.ae2opti$ticksSinceSend++;
            }
            return;
        }
        this.ae2opti$sending = false;

        final long elapsed = System.nanoTime() - this.ae2opti$startNanos;
        final int interval = Math.max(1, cfg.cpuGuiSlowInterval);
        // まとめて送った分は重くなるので、1tickあたりに直してから比べる（遅いまま戻らなくなるのを防ぐ）
        final long perTick = elapsed / Math.max(1, Math.min(this.ae2opti$ticksSinceSend, interval));
        this.ae2opti$ticksSinceSend = 1;

        final boolean heavySend = cfg.cpuGuiSlowSendMicros > 0 && perTick >= cfg.cpuGuiSlowSendMicros * 1_000L;
        final boolean heavyServer = cfg.cpuGuiSlowMspt > 0 && ServerLoad.averageMspt() >= cfg.cpuGuiSlowMspt;
        this.ae2opti$wait = (heavySend || heavyServer) ? interval - 1 : 0;
    }
}
