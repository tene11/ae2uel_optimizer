package ae2opti.p2p;

import ae2opti.AE2OptiConfig;
import appeng.api.config.Actionable;
import appeng.api.config.PowerUnits;
import appeng.me.GridAccessException;
import appeng.parts.p2p.PartP2PFEPower;
import appeng.tile.networking.TileController;
import appeng.tile.networking.TileEnergyAcceptor;
import appeng.tile.powersink.IExternalPowerSink;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * P2P FE トンネルの入口の配分処理を、{@code long} で行う版（設定 {@code p2p.newDistribution}）。
 *
 * <p>元の AE2UEL（v0.56.6 / v0.56.7）の {@code PartP2PFEPower.InputEnergyStorage.receiveEnergy} と同じ手順で配る：</p>
 * <ol>
 *     <li>1周目：全出口に {@code max(1, 量 ÷ 出口数)} ずつ渡す。受け取られなかった分は overflow に足す。
 *     合計が量に届いたら終わる。</li>
 *     <li>2周目：overflow が残っていれば、各出口に overflow をまとめて渡す。0 になったら終わる。</li>
 *     <li>本番（simulate でない）なら、運んだ量に応じてトンネル税を払う（{@link TunnelTaxes}）。</li>
 * </ol>
 *
 * <p>元との違い：</p>
 * <ul>
 *     <li>[long] 量・配分・合計をすべて {@code long} で扱う。Flux Networks や DE のパイロンからは
 *     {@code int} を超える量が入ってくる（{@code int} の FE の口からはこれまでどおり {@code int} まで）。
 *     渡し方は {@link #offer} を参照。</li>
 *     <li>[P-5] 出口一覧を tick 内で使い回す（{@code p2p.cacheOutputs}）。</li>
 *     <li>[P-7] 出口を回すキューを呼び出しごとのローカルにし、同じ入口が配っている最中に入れ子で呼ばれたら 0 を返す。
 *     元は1つのキューを共有していたので、出口から同じ入口に電力が戻るループ配線だと中身が混ざっていた。</li>
 *     <li>[不具合修正] 2周目に渡す量を「量 − 1周目の合計」で止める。元は出口数より少ない量のときに、
 *     渡された量より多く受け取ったと返すことがあった（FE の約束違反）。</li>
 *     <li>[P-2] {@code p2p.skipEmptySecondPass}：1周目に何も受け取らなかった出口には、2周目の量が
 *     1周目以下のときだけ渡さない（同じ量か少ない量をもう一度断られるだけなので）。
 *     少ない量は断るが多い量なら受け取る相手（GTCEu のコンバーターなど）には、元どおり2周目で渡す。</li>
 *     <li>[P-3] {@code p2p.skipFullOutputs}：同じ tick で 0 を返した出口は、その tick の残りは
 *     「断った量以下」の量なら呼ばずに 0 とする。それより多い量なら、もう一度渡してみる。</li>
 * </ul>
 */
public final class FEP2PDistributor {

    private static final String AE_FORGE_ENERGY_ADAPTER = "appeng.tile.powersink.ForgeEnergyAdapter";

    /**
     * {@code PartP2PFEPowerMixin} が当たっているか（{@code mixins.p2pFePower=false} や適用失敗なら false）。
     * false のときに {@link #state} でキャストすると ClassCastException になる。
     * このクラスが初めて使われるのはゲーム中（late mixin の適用後）なので、ここで決めてよい。
     */
    private static final boolean MIXIN_APPLIED = FEP2PState.class.isAssignableFrom(PartP2PFEPower.class);

    private FEP2PDistributor() {
    }

    /**
     * ほかの Mod（Flux Networks・DE のパイロンなど）から {@link #distribute} を呼んでよいか。
     * mixin が当たっていて、{@code p2p.newDistribution} がオンのときだけ true。
     * false なら呼び出し側は元の処理（FE の capability など）に任せること。
     */
    public static boolean isAvailable() {
        return MIXIN_APPLIED && AE2OptiConfig.p2p.newDistribution;
    }

    /**
     * 入口 {@code input} に {@code maxReceive} FE を入れる（＝出口に配る）。
     *
     * @return 実際に配った量（simulate なら配れる量）。0 以上 {@code maxReceive} 以下
     */
    public static long distribute(PartP2PFEPower input, long maxReceive, boolean simulate) {
        if (maxReceive <= 0 || !MIXIN_APPLIED) {
            return 0;
        }
        final FEP2PState in = state(input);
        if (in.ae2opti$isDistributing()) {
            // [P-7] ループ配線などで、配っている最中の入口にもう一度入ってきた。受け取らない
            return 0;
        }

        final long now = currentTick(input);
        final List<PartP2PFEPower> outputs;
        try {
            outputs = outputsOf(input, in, now);
        } catch (GridAccessException e) {
            return 0; // 元と同じ（網につながっていなければ 0）
        }
        final int count = outputs.size();
        if (count == 0) {
            return 0;
        }

        final boolean skipFull = AE2OptiConfig.p2p.skipFullOutputs && now >= 0;
        long total = 0;
        in.ae2opti$setDistributing(true);
        try {
            final long perOutput = Math.max(1L, maxReceive / count);
            long overflow = 0;
            // [P-2] 1周目で何も受け取らなかった出口（添え字 → 断った量）。誰も断らなければ null のまま
            final boolean skipRefused = AE2OptiConfig.p2p.skipEmptySecondPass;
            long[] refused = null;

            int index = 0;
            for (PartP2PFEPower out : outputs) {
                final long received = offerTracked(out, perOutput, simulate, skipFull, now);
                if (skipRefused && received <= 0) {
                    if (refused == null) {
                        refused = new long[count];
                    }
                    refused[index] = perOutput;
                }
                overflow += perOutput - received;
                total += received;
                index++;
                if (total >= maxReceive) {
                    break;
                }
            }

            // 元は止めていなかった（量 < 出口数のときに量より多く渡すことがある）
            overflow = Math.min(overflow, maxReceive - total);
            if (overflow > 0) {
                index = 0;
                for (PartP2PFEPower out : outputs) {
                    // [P-2] 1周目に同じ量以上を断った出口には渡さない
                    final boolean alreadyRefused = refused != null && refused[index] >= overflow;
                    index++;
                    if (alreadyRefused) {
                        continue;
                    }
                    final long received = offerTracked(out, overflow, simulate, skipFull, now);
                    overflow -= received;
                    total += received;
                    if (overflow <= 0) {
                        break;
                    }
                }
            }
        } finally {
            in.ae2opti$setDistributing(false);
        }

        if (!simulate && total > 0) {
            TunnelTaxes.charge(input, in, total, now);
        }
        return total;
    }

    /** 表示用：全出口の先の FE の合計（[P-8] long で足して int の上限で止める） */
    public static int sumEnergyStored(PartP2PFEPower input, boolean max) {
        long sum = 0;
        try {
            for (PartP2PFEPower out : input.getOutputs()) {
                final IEnergyStorage storage = state(out).ae2opti$getAttachedStorage();
                sum += max ? storage.getMaxEnergyStored() : storage.getEnergyStored();
                if (sum >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        } catch (GridAccessException e) {
            return 0;
        }
        return (int) Math.max(0L, sum);
    }

    // ------------------------------------------------------------------------

    /**
     * [P-3] 付きの {@link #offer}。この tick に {@code amount} 以上の量を断った出口は、呼ばずに 0 を返す。
     * 断った量より多ければ渡してみる（少ない量は断るが多い量なら受け取る相手がいるため）。
     */
    private static long offerTracked(PartP2PFEPower out, long amount, boolean simulate, boolean skipFull, long now) {
        if (skipFull && amount <= state(out).ae2opti$getRejected(now)) {
            return 0;
        }
        final long received = offer(out, amount, simulate);
        if (skipFull && received <= 0) {
            state(out).ae2opti$setRejected(now, amount);
        }
        return received;
    }

    /**
     * 出口 {@code out} の先に {@code amount} FE を渡す。相手によって渡し方を変える：
     * <ol>
     *     <li>{@link ILongEnergyReceiver}（DE のインジェクター・パイロン、PackagedDraconic など）：{@code long} で1回。
     *     （{@code p2p.longReceivers}）</li>
     *     <li>AE2 の電力を受け取るタイル（{@code ForgeEnergyAdapter} 経由のもの）：{@code injectExternalPower} に
     *     {@code double} でそのまま入れる。中身は元の {@code ForgeEnergyAdapter.receiveEnergy} と同じ。
     *     コントローラー／Energy Acceptor は、{@code p2p.acceptorBuffer} がオンならバッファに貯める（[P-4]）。</li>
     *     <li>それ以外の FE：{@code int} に収まればそのまま。収まらなければ {@code p2p.splitIntTransfer} に従って
     *     {@code int} の上限ごとに分けて渡す（相手が全部受け取らなくなるか、回数の上限まで）。
     *     simulate のときは1回分だけ答える（試すだけの呼び出しを繰り返しても、相手の状態が変わらないため）。</li>
     * </ol>
     */
    static long offer(PartP2PFEPower out, long amount, boolean simulate) {
        final FEP2PState st = state(out);
        final IEnergyStorage storage = st.ae2opti$getAttachedStorage();

        if (AE2OptiConfig.p2p.longReceivers && storage instanceof ILongEnergyReceiver) {
            return Math.max(0L, ((ILongEnergyReceiver) storage)
                    .ae2opti$receiveLong(facingOf(out), amount, simulate));
        }

        final TileEntity te = st.ae2opti$getAttachedTile();
        if (te != null) {
            if (AE2OptiConfig.p2p.longReceivers && te instanceof ILongEnergyReceiver) {
                return Math.max(0L, ((ILongEnergyReceiver) te).ae2opti$receiveLong(facingOf(out), amount, simulate));
            }
            if (te instanceof IExternalPowerSink && isAeAdapter(storage)) {
                if (AE2OptiConfig.p2p.acceptorBuffer && !te.getWorld().isRemote
                        && (te instanceof TileController || te instanceof TileEnergyAcceptor)) {
                    return AcceptorBuffers.offer(te, amount, simulate);
                }
                final double overflow = ((IExternalPowerSink) te).injectExternalPower(PowerUnits.RF, (double) amount,
                        simulate ? Actionable.SIMULATE : Actionable.MODULATE);
                return Math.max(0L, (long) (amount - overflow));
            }
        }

        if (amount <= Integer.MAX_VALUE) {
            return storage.receiveEnergy((int) amount, simulate);
        }
        if (!AE2OptiConfig.p2p.splitIntTransfer || simulate) {
            return storage.receiveEnergy(Integer.MAX_VALUE, simulate);
        }
        long received = 0;
        final int maxCalls = Math.max(1, AE2OptiConfig.p2p.splitMaxCallsPerTransfer);
        for (int i = 0; i < maxCalls && received < amount; i++) {
            final int chunk = (int) Math.min(Integer.MAX_VALUE, amount - received);
            final int got = storage.receiveEnergy(chunk, false);
            if (got <= 0) {
                break;
            }
            received += got;
            if (got < chunk) {
                break;
            }
        }
        return received;
    }

    private static List<PartP2PFEPower> outputsOf(PartP2PFEPower input, FEP2PState in, long now)
            throws GridAccessException {
        final boolean useCache = AE2OptiConfig.p2p.cacheOutputs && now >= 0;
        if (useCache) {
            final List<PartP2PFEPower> cached = in.ae2opti$getCachedOutputs(now);
            if (cached != null) {
                return cached;
            }
        }
        final List<PartP2PFEPower> list = new ArrayList<>();
        for (PartP2PFEPower out : input.getOutputs()) {
            list.add(out);
        }
        if (useCache) {
            in.ae2opti$setCachedOutputs(list, now);
        }
        return list;
    }

    /** 出口の先のタイルから見た面（元の getAttachedEnergyStorage と同じ向き） */
    private static EnumFacing facingOf(PartP2PFEPower out) {
        return out.getSide().getOpposite().getFacing();
    }

    private static boolean isAeAdapter(IEnergyStorage storage) {
        return storage != null && AE_FORGE_ENERGY_ADAPTER.equals(storage.getClass().getName());
    }

    /** 入口のワールドの tick。分からなければ -1（tick 単位のキャッシュを使わない） */
    static long currentTick(PartP2PFEPower part) {
        final TileEntity tile = part.getTile();
        if (tile == null || tile.getWorld() == null) {
            return -1;
        }
        return tile.getWorld().getTotalWorldTime();
    }

    static FEP2PState state(PartP2PFEPower part) {
        return (FEP2PState) (Object) part;
    }
}
