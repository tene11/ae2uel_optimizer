package ae2opti.betterp2p.mixin;

import appeng.me.cache.P2PCache;
import appeng.parts.p2p.PartP2PTunnel;
import com.google.common.collect.Multimap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link GridServerCacheMixin} から使う、AE2UEL の {@link P2PCache} の private メンバーへの入口。
 * Better P2P の修正でしか使わないので、Better P2P 用の mixin 設定（mixins.ae2opti.betterp2p.json）に入れている。
 */
@Mixin(value = P2PCache.class, remap = false)
public interface P2PCacheAccessor {

    /** 周波数 → 出口。入口の無い（取り残された）出口もここには入っている */
    @SuppressWarnings("rawtypes")
    @Accessor("outputs")
    Multimap<Short, PartP2PTunnel> ae2opti$getOutputs();

    /**
     * その周波数の入口・出口すべてに {@code onTunnelNetworkChange()} を送る（private の {@code updateTunnel}）。
     * AE2 がパーツを外したとき（{@code removeNode}）に、元の周波数に対してやっているのと同じ処理。
     */
    @Invoker("updateTunnel")
    void ae2opti$updateTunnel(short freq, boolean updateOutputs, boolean configChange);
}
