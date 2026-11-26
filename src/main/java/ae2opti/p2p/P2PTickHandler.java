package ae2opti.p2p;

import ae2opti.Tags;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * サーバーの tick の終わりに、[P-6] のまとめ払いと [P-4] のバッファを流す。
 *
 * <p>イベント購読に登録されるクラスなので、メソッドの引数に AE2 のクラスを置かないこと
 * （FML が起動時に引数の型を読み込み、late mixin より先に AE2 のクラスが読み込まれるのを避けるため）。
 * AE2 の TickHandler より後に動くよう LOWEST にしている。</p>
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class P2PTickHandler {

    private P2PTickHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        TunnelTaxes.flush();
        AcceptorBuffers.flush();
    }

    /** サーバー停止時に、ワールドのものへの参照を捨てる */
    public static void clear() {
        TunnelTaxes.clear();
        AcceptorBuffers.clear();
    }
}
