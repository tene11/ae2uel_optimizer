package ae2opti.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.debug.FiberCrosstalkDetector;
import appeng.api.networking.IGridNode;
import appeng.api.util.AEPartLocation;
import appeng.me.GridConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1-E：混線の検出（デバッグ用）。
 *
 * {@link GridConnection#create} の中で2つの grid が合体した直後に、
 * 石英ファイバーの内側ノードと外側ノードが同じ grid に入っていないかを調べる。
 * 合体から分離までが同じ呼び出しの中で終わる「一瞬の合体」も、ここなら捕まえられる。
 *
 * <ul>
 *   <li>前：{@code new GridConnection(...)} の直前（null・既存接続・セキュリティの例外チェックが済んだ後）で、
 *       両側のノードがそれぞれどの grid にいたかを記録する。ここより後で例外は投げられないので、前後の対応がずれない。</li>
 *   <li>後：{@code return} の直前で、合体だったときだけ合体後の grid を調べる。</li>
 * </ul>
 *
 * 設定 {@code AE2OptiConfig.debug.fiberCrosstalk} が false（デフォルト）のときは何もしない。
 * 元の処理は一切変えない（{@code @Inject} のみ、cancel なし）。
 * CrazyAE が {@code canSupportMoreChannels} に {@code @Inject} しているが、触る場所が違うので重ならない。
 */
@Mixin(value = GridConnection.class, remap = false)
public abstract class GridConnectionMixin {

    @Inject(method = "create", at = @At(value = "NEW", target = "appeng/me/GridConnection"))
    private static void ae2opti$beforeConnect(IGridNode aNode, IGridNode bNode, AEPartLocation fromAtoB,
                                                 CallbackInfoReturnable<GridConnection> cir) {
        if (AE2OptiConfig.debug.fiberCrosstalk) {
            FiberCrosstalkDetector.beforeConnect(aNode, bNode);
        }
    }

    @Inject(method = "create", at = @At("RETURN"))
    private static void ae2opti$afterConnect(IGridNode aNode, IGridNode bNode, AEPartLocation fromAtoB,
                                                CallbackInfoReturnable<GridConnection> cir) {
        // 設定を途中で切り替えても前後の対応がずれないよう、ここでは設定を見ずに検出側へ渡す。
        FiberCrosstalkDetector.afterConnect(cir.getReturnValue());
    }
}
