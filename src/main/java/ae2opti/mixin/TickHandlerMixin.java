package ae2opti.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.ghost.GhostNodeGuard;
import appeng.hooks.TickHandler;
import appeng.tile.AEBaseTile;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1-H：幽霊ノード対策。
 *
 * <p>{@link TickHandler#onTick} の ServerTick END で、初期化待ちのタイルに {@code bt.onReady()} を呼ぶところを包む。
 * 元の処理は {@code isInvalid()} しか見ていないので、待ち行列に入ったあとでチャンクやディメンションが
 * アンロードされたタイルにもノードが作られてしまう。ここで {@link GhostNodeGuard#allowReady} に通らないタイルは飛ばす。</p>
 *
 * <p>飛ばしたタイルは待ち行列から消えるだけ（元の処理が poll 済み）。そのタイルはもうワールドに無いので、
 * チャンクが読み込み直されたときは新しいタイルが改めて待ち行列に入る。</p>
 *
 * <p>{@code onTick} の中で {@code AEBaseTile.onReady()} を呼んでいるのはこの1か所だけ。
 * {@code @WrapOperation} なので、他の Mod が同じ場所を包んでも重ねがけできる。</p>
 */
@Mixin(value = TickHandler.class, remap = false)
public abstract class TickHandlerMixin {

    @WrapOperation(method = "onTick",
            at = @At(value = "INVOKE", target = "Lappeng/tile/AEBaseTile;onReady()V"))
    private void ae2opti$skipGhostTile(AEBaseTile tile, Operation<Void> original) {
        if (AE2OptiConfig.grid.skipUnloadedTileInit && !GhostNodeGuard.allowReady(tile)) {
            return;
        }
        original.call(tile);
    }
}
