package ae2opti.p2p;

import appeng.parts.p2p.PartP2PFEPower;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.List;

/**
 * {@code PartP2PFEPowerMixin} が {@link PartP2PFEPower} に足す、ae2opti 用の状態と入口。
 * 使うときは {@code (FEP2PState) (Object) part} でキャストする。
 */
public interface FEP2PState {

    /** 元の private {@code getAttachedEnergyStorage()}（P-1 のキャッシュも効く）。つながっていなければ何もしないストレージ */
    IEnergyStorage ae2opti$getAttachedStorage();

    /** 出口の先の TileEntity。パーツが動いていない（isActive でない）か、何もなければ null */
    TileEntity ae2opti$getAttachedTile();

    // ---- P-3：この tick にこの出口が断った（0 を返した）量 ----

    /**
     * @return {@code tick} にこの出口が 0 を返した量のうち、いちばん大きいもの。その tick に断っていなければ 0
     */
    long ae2opti$getRejected(long tick);

    /** {@code tick} にこの出口が {@code amount} を断った（0 を返した）ことを記録する。大きいほうを覚える */
    void ae2opti$setRejected(long tick, long amount);

    // ---- P-5：入口の出口一覧（tick 内で使い回す） ----

    /** @return {@code tick} に作った一覧があればそれ、なければ null */
    List<PartP2PFEPower> ae2opti$getCachedOutputs(long tick);

    void ae2opti$setCachedOutputs(List<PartP2PFEPower> outputs, long tick);

    // ---- P-7：配っている最中か（入れ子で呼ばれたら断る） ----

    boolean ae2opti$isDistributing();

    void ae2opti$setDistributing(boolean distributing);

    // ---- トンネル税の上限用：この tick に払った量（AE） ----

    long ae2opti$getTaxTick();

    double ae2opti$getTaxPaid();

    void ae2opti$setTaxPaid(long tick, double paid);
}
