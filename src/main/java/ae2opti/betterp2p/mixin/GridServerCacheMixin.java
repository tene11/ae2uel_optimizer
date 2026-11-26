package ae2opti.betterp2p.mixin;

import ae2opti.AE2OptiConfig;
import appeng.api.parts.IPartHost;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.cache.P2PCache;
import appeng.parts.p2p.PartP2PTunnel;
import appeng.parts.p2p.PartP2PTunnelME;
import appeng.util.Platform;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.projecturanus.betterp2p.BetterP2P;
import com.projecturanus.betterp2p.network.data.GridServerCache;
import com.projecturanus.betterp2p.network.data.P2PLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextComponentTranslation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Better P2P（AE2-UEL 版、v1.3.2 / v1.3.3。この2つで GridServerCache は同じ）の不具合修正。
 * Better P2P は Kotlin 製だが、中身は普通の JVM クラスなので mixin で触れる。
 * Better P2P が入っているときだけ読み込む（{@code AE2OptiMixinLoader#shouldMixinConfigQueue}）。
 *
 * <ol>
 *     <li><b>周波数の衝突</b>（{@code p2p.betterP2PSafeFrequency}）：
 *     {@code linkP2P} は新しい周波数を {@code System.currentTimeMillis().toShort()} で作り、重複を確かめない。
 *     既存の入口と重なると、その無関係な入口を出口に変えてしまう（乗っ取り）。
 *     AE2UEL の {@link P2PCache#newFrequency()}（0 と既存の入口を避ける）で作り直し、
 *     さらに入口の無い出口が残っている周波数も避ける。</li>
 *     <li><b>未知の P2P の種類で NPE</b>（{@code p2p.betterP2PUnknownTypeCheck}）：
 *     入口と出口の種類が違うと {@code getP2PFromClass(input.javaClass)!!} を呼ぶ。
 *     Better P2P が知らない種類（他のアドオンの P2P など）だとここで NPE になるので、
 *     先に確かめて、プレイヤーにメッセージを出して null（失敗）を返す。</li>
 *     <li><b>リンクのたびにパーツを付け直す負荷</b>（{@code p2p.betterP2PInPlaceRelink}）：
 *     {@code updateP2P} は毎回 {@code removePart} → {@code addPart} をするので、ノードが作り直されて網のリパスが起きる。
 *     入口／出口の向きが変わらないときは、AE2 のメモリーカード処理と同じく
 *     {@link P2PCache#updateFreq} で周波数だけを変える。</li>
 * </ol>
 */
@Mixin(value = GridServerCache.class, remap = false)
public abstract class GridServerCacheMixin {

    /** 新しい周波数を作り直す回数の上限（取り残された出口を避けるため） */
    @Unique
    private static final int AE2OPTI$MAX_FREQUENCY_TRIES = 64;

    @Shadow
    @Final
    private Map<P2PLocation, PartP2PTunnel<?>> listP2P;

    @Shadow
    @Final
    private EntityPlayer player;

    // ---- 1. 周波数の衝突 ----

    // 要ローカル検証: linkP2P の中で currentTimeMillis を呼んでいるのは1か所だけ／その時点で P2PCache のローカル変数は cache の1つだけ
    @ModifyExpressionValue(method = "linkP2P",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;currentTimeMillis()J"))
    private long ae2opti$safeFrequency(long original, @Local P2PCache cache) {
        if (!AE2OptiConfig.p2p.betterP2PSafeFrequency) {
            return original;
        }
        // 戻り値はこの後 toShort() されるので、short の値をそのまま long にして返せば、その値になる
        return ae2opti$newFrequency(cache);
    }

    @Unique
    private static short ae2opti$newFrequency(P2PCache cache) {
        final P2PCacheAccessor accessor = (P2PCacheAccessor) (Object) cache;
        short frequency;
        int tries = 0;
        do {
            frequency = cache.newFrequency(); // 0 と、入口がある周波数は避けてくれる
            tries++;
        } while (accessor.ae2opti$getOutputs().containsKey(frequency) && tries < AE2OPTI$MAX_FREQUENCY_TRIES);
        return frequency;
    }

    // ---- 2. 未知の P2P の種類 ----

    @Inject(method = "linkP2P", at = @At("HEAD"), cancellable = true)
    private void ae2opti$rejectUnknownType(P2PLocation inputIndex, P2PLocation outputIndex,
                                           CallbackInfoReturnable<?> cir) {
        if (!AE2OptiConfig.p2p.betterP2PUnknownTypeCheck) {
            return;
        }
        final PartP2PTunnel<?> input = this.listP2P.get(inputIndex);
        final PartP2PTunnel<?> output = this.listP2P.get(outputIndex);
        if (input == null || output == null || input.getClass() == output.getClass()) {
            // どちらも元の処理のまま（null なら元も null を返す。同じ種類なら getP2PFromClass は呼ばれない）
            return;
        }
        if (BetterP2P.INSTANCE.getProxy().getP2PFromClass(input.getClass()) == null) {
            this.player.sendMessage(new TextComponentTranslation("ae2opti.betterp2p.unknown_type",
                    input.getClass().getSimpleName()));
            cir.setReturnValue(null);
        }
    }

    // ---- 3. その場でリンクを更新する ----

    @Inject(method = "updateP2P", at = @At("HEAD"), cancellable = true)
    private void ae2opti$updateInPlace(P2PLocation key, PartP2PTunnel<?> tunnel, short frequency, boolean output,
                                       String name, CallbackInfoReturnable<PartP2PTunnel<?>> cir) {
        if (!AE2OptiConfig.p2p.betterP2PInPlaceRelink) {
            return;
        }
        // 入口／出口が入れ替わるときは、AE2 自身もパーツを付け直しているので元の処理に任せる
        if (tunnel.isOutput() != output) {
            return;
        }
        // ME P2P は外側のノードの接続、インターフェース P2P は中身の引き継ぎ（linkP2P の handleInterface）が
        // パーツの付け直しを前提にしているので、元の処理に任せる
        if (tunnel instanceof PartP2PTunnelME || tunnel instanceof IInterfaceHost) {
            return;
        }
        final IPartHost host = tunnel.getHost();
        if (host == null || host.getPart(tunnel.getSide()) != tunnel) {
            return;
        }
        final P2PCache cache;
        try {
            cache = tunnel.getProxy().getP2P();
        } catch (GridAccessException e) {
            return;
        }

        final short oldFrequency = tunnel.getFrequency();
        // 古い周波数から外し、新しい周波数に入れ、新しい周波数の全員に onTunnelNetworkChange を送る
        cache.updateFreq(tunnel, frequency);
        if (oldFrequency != frequency) {
            // パーツを外したとき（removeNode）と同じく、元の周波数に残った側にも知らせる
            ((P2PCacheAccessor) (Object) cache).ae2opti$updateTunnel(oldFrequency, output, false);
        }
        if (!ae2opti$isBlank(name)) {
            tunnel.setCustomName(name);
        }
        tunnel.onTunnelNetworkChange();
        host.markForSave();
        final TileEntity te = tunnel.getTile();
        if (te != null && te.getWorld() != null) {
            Platform.notifyBlocksOfNeighbors(te.getWorld(), te.getPos());
        }

        ((GridServerCache) (Object) this).markDirty(key, tunnel);
        cir.setReturnValue(tunnel);
    }

    /** Kotlin の {@code CharSequence.isBlank()} と同じ判定 */
    @Unique
    private static boolean ae2opti$isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                return false;
            }
        }
        return true;
    }
}
