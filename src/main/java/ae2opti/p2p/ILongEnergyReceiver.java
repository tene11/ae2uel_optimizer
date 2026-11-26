package ae2opti.p2p;

import net.minecraft.util.EnumFacing;

/**
 * FE（RF）を {@code long} のまま受け取れる相手。ae2opti の mixin が、元は {@code int} の口しかない
 * 他 Mod のタイル／エネルギーストレージに足す（DE のフュージョン用インジェクター・パイロン、
 * PackagedDraconic の Marked Injector など）。
 *
 * <p>P2P FE の出口は、隣のタイル、またはそのタイルから得た {@code IEnergyStorage} がこれを実装していれば、
 * {@code int} に分けずに1回で渡す（{@link FEP2PDistributor}）。実装側は「元の {@code receiveEnergy} の計算式を
 * そのまま {@code long} で行う」こと。1回あたりの上限（充電速度など）も元どおりに守る。</p>
 */
public interface ILongEnergyReceiver {

    /**
     * @param side      受け取る面（相手から見た面。P2P の出口の向きの反対）
     * @param maxReceive 渡したい量（FE）。0 より大きい
     * @param simulate  true なら試すだけで、状態を変えない
     * @return 受け取った（受け取れる）量。0 以上
     */
    long ae2opti$receiveLong(EnumFacing side, long maxReceive, boolean simulate);
}
