package ae2opti.p2p;

import ae2opti.AE2OptiConfig;
import appeng.api.config.Actionable;
import appeng.api.config.PowerUnits;
import appeng.tile.powersink.IExternalPowerSink;
import net.minecraft.tileentity.TileEntity;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * [P-4] P2P FE の出口から AE2 のコントローラー／Energy Acceptor に入る電力を、いったん貯めて
 * tick の終わりに1回だけ {@code injectExternalPower} する（設定 {@code p2p.acceptorBuffer}、デフォルトでオン）。
 *
 * <p>重さは流す量ではなく {@code injectPower} が呼ばれる回数で決まるので、1台あたり 1tick に1回まで減らせる。</p>
 * <ul>
 *     <li>受け取れる量（simulate の答えも）＝「網が今受け取れる量」と {@code p2p.acceptorBufferRF} の小さいほう
 *     − 貯まっている量。</li>
 *     <li>「網が今受け取れる量」は、その tick に最初に渡されたときに1回だけ
 *     {@code injectExternalPower(SIMULATE)} で聞いて、その tick の間は使い回す。
 *     網に入りきらない分まで受け取って、その分のトンネル税で入口の網の電力を使い切ってしまうのを防ぐ。</li>
 *     <li>網が満タンで入りきらなかった分はバッファに残り、その分だけ次の tick に受け取れる量が減る。</li>
 *     <li>注意：網に届くのが最大 1tick 遅れる。タイルが壊れたりチャンクが読み込まれていなかったりすると、
 *     バッファの中身は捨てる（網の空きまでしか受け取らないので、ふつうは 1tick 分以下）。</li>
 *     <li>同じ網にコントローラー／Energy Acceptor が複数あると、それぞれが網の空きまで受け取るので、
 *     合計では空きを超えることがある。超えた分はバッファに残るだけ。</li>
 * </ul>
 */
public final class AcceptorBuffers {

    /** 貯まっている量が、これ以下なら空とみなす（FE） */
    private static final double EPSILON = 0.000001;

    /** タイル → バッファ */
    private static final Map<TileEntity, Buffer> BUFFERS = new WeakHashMap<>();

    private static final class Buffer {
        /** 貯まっている量（FE） */
        double stored;
        /** {@link #room} を聞いた tick */
        long roomTick = Long.MIN_VALUE;
        /** その tick の初めに、網が受け取れた量（FE、{@code p2p.acceptorBufferRF} まで） */
        double room;
    }

    private AcceptorBuffers() {
    }

    static long offer(TileEntity te, long amount, boolean simulate) {
        Buffer buffer = BUFFERS.get(te);
        if (buffer == null) {
            buffer = new Buffer();
            BUFFERS.put(te, buffer);
        }
        final long now = te.getWorld().getTotalWorldTime();
        if (buffer.roomTick != now) {
            buffer.roomTick = now;
            buffer.room = roomOf(te);
        }
        final double free = Math.min(AE2OptiConfig.p2p.acceptorBufferRF, buffer.room) - buffer.stored;
        if (free < 1) {
            return 0;
        }
        final long accepted = (long) Math.min((double) amount, Math.floor(free));
        if (!simulate && accepted > 0) {
            buffer.stored += accepted;
        }
        return accepted;
    }

    /** 網（またはタイル自身）が今受け取れる量（FE）。{@code p2p.acceptorBufferRF} まで */
    private static double roomOf(TileEntity te) {
        final double max = AE2OptiConfig.p2p.acceptorBufferRF;
        final double leftover = ((IExternalPowerSink) te).injectExternalPower(PowerUnits.RF, max, Actionable.SIMULATE);
        return Math.max(0.0, max - leftover);
    }

    /** サーバーの tick の終わりに呼ばれる */
    public static void flush() {
        if (BUFFERS.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<TileEntity, Buffer>> it = BUFFERS.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<TileEntity, Buffer> e = it.next();
            final TileEntity te = e.getKey();
            final Buffer buffer = e.getValue();
            if (buffer.stored <= EPSILON) {
                // simulate だけで作られたもの、または流し終わったもの
                it.remove();
                continue;
            }
            if (te == null || te.isInvalid() || te.getWorld() == null || !te.getWorld().isBlockLoaded(te.getPos())
                    || !(te instanceof IExternalPowerSink)) {
                it.remove();
                continue;
            }
            final double leftover = ((IExternalPowerSink) te).injectExternalPower(PowerUnits.RF, buffer.stored,
                    Actionable.MODULATE);
            if (leftover <= EPSILON) {
                it.remove();
            } else {
                buffer.stored = leftover;
            }
        }
    }

    public static void clear() {
        BUFFERS.clear();
    }
}
