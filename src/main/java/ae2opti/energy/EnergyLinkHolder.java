package ae2opti.energy;

import appeng.api.networking.energy.IEnergyGridProvider;
import com.google.common.collect.Multiset;

/**
 * {@code EnergyGridCacheMixin} が EnergyGridCache に足す、[1-B] 用の入れ物。
 * 使うときは {@code (EnergyLinkHolder) energyGridCache} でキャストする。
 *
 * <p>mixin パッケージの外に置くこと。</p>
 */
public interface EnergyLinkHolder {

    /** この網が最後に覚えた「つながった網の一覧」。まだ無ければ null */
    EnergyLinks.Component ae2opti$getLinks();

    /** つながった網の一覧を覚えさせる（同じ一覧に入っているすべての網に同じものを入れる） */
    void ae2opti$setLinks(EnergyLinks.Component links);

    /**
     * この網の {@code energyGridProviders}（この網に入っている石英ファイバーなど）そのもの。
     * 元の {@code providers()} と同じものを返す。読むだけにして、書き換えないこと。
     */
    Multiset<IEnergyGridProvider> ae2opti$providerMultiset();

    // ---- energy.linkedGridTickOrder 用の「この tick は満タン／空」の印 ----

    /** {@code tick} に、つながったほかの網からの注入を1も受け取らなかった（その後まだ電力が取り出されていない）なら true */
    boolean ae2opti$isFull(int tick);

    void ae2opti$markFull(int tick);

    /** {@code tick} に、つながったほかの網からの取り出しで1も出さなかった（その後まだ電力が入れられていない）なら true */
    boolean ae2opti$isEmpty(int tick);

    void ae2opti$markEmpty(int tick);
}
