package ae2opti.p2p;

import ae2opti.AE2OptiConfig;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.PowerUnits;
import appeng.core.AEConfig;
import appeng.me.GridAccessException;
import appeng.parts.p2p.PartP2PFEPower;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * P2P FE のトンネル税（運んだ量に応じて、入口の ME 網から AE 電力を引く）。
 *
 * <p>元の AE2UEL（{@code PartP2PTunnel.queueTunnelDrain}）と同じ計算：
 * {@code RF → AE 換算(運んだ FE × AEConfig.TUNNEL_POWER_LOSS)}（デフォルトで運んだ量の 2.5% ぶんの AE）。</p>
 * <ul>
 *     <li>{@code p2p.tunnelTaxMultiplier}：倍率（1.0 = 元と同じ、0 = 税なし）</li>
 *     <li>{@code p2p.tunnelTaxCapPerTick}：トンネル1つ・1tick あたりの上限（AE、0 = 上限なし）</li>
 *     <li>[P-6] {@code p2p.batchTunnelTax}：本番の受け渡しごとではなく、tick の終わりにまとめて払う
 *     （{@code extractAEPower} の回数が減る。払う時点だけが tick の終わりにずれる）</li>
 * </ul>
 */
public final class TunnelTaxes {

    /** [P-6] まとめ払い待ちの量（FE）。入口ごと */
    private static final Map<PartP2PFEPower, double[]> PENDING = new WeakHashMap<>();

    private TunnelTaxes() {
    }

    static void charge(PartP2PFEPower input, FEP2PState state, long transferredFe, long now) {
        if (AE2OptiConfig.p2p.batchTunnelTax) {
            double[] pending = PENDING.get(input);
            if (pending == null) {
                pending = new double[1];
                PENDING.put(input, pending);
            }
            pending[0] += transferredFe;
            return;
        }
        pay(input, state, transferredFe, now);
    }

    /** サーバーの tick の終わりに呼ばれる（[P-6]） */
    public static void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<PartP2PFEPower, double[]>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<PartP2PFEPower, double[]> e = it.next();
            it.remove();
            final PartP2PFEPower input = e.getKey();
            if (input != null && e.getValue()[0] > 0) {
                pay(input, FEP2PDistributor.state(input), e.getValue()[0], FEP2PDistributor.currentTick(input));
            }
        }
    }

    public static void clear() {
        PENDING.clear();
    }

    private static void pay(PartP2PFEPower input, FEP2PState state, double transferredFe, long now) {
        double ae = PowerUnits.RF.convertTo(PowerUnits.AE, transferredFe * AEConfig.TUNNEL_POWER_LOSS)
                * AE2OptiConfig.p2p.tunnelTaxMultiplier;
        if (!(ae > 0)) {
            return;
        }
        final double cap = AE2OptiConfig.p2p.tunnelTaxCapPerTick;
        if (cap > 0) {
            double paid = state.ae2opti$getTaxTick() == now ? state.ae2opti$getTaxPaid() : 0;
            final double left = cap - paid;
            if (left <= 0) {
                return;
            }
            ae = Math.min(ae, left);
            state.ae2opti$setTaxPaid(now, paid + ae);
        }
        try {
            input.getProxy().getEnergy().extractAEPower(ae, Actionable.MODULATE, PowerMultiplier.ONE);
        } catch (GridAccessException ignored) {
            // 元と同じく、網が無ければ払わない
        }
    }
}
