package ae2opti.debug;

import ae2opti.AE2OptiConfig;
import ae2opti.Tags;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 1-E：サーバーの tick の終わりを {@link FiberCrosstalkDetector} に知らせるだけのクラス。
 *
 * イベント購読に登録されるクラスなので、メソッドの引数に AE2 のクラスを置かないこと
 * （FML が起動時に引数の型を読み込み、late mixin より先に AE2 のクラスが読み込まれるのを避けるため）。
 *
 * AE2 の TickHandler（初期化待ちタイルの onReady もここで走る）より後に動くよう、LOWEST にしている。
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class FiberCrosstalkTickHandler {

    /** 一度でも有効にしたか。無効のままなら AE2 のクラスに一切触れない。 */
    private static boolean everEnabled;

    private FiberCrosstalkTickHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (AE2OptiConfig.debug.fiberCrosstalk) {
            everEnabled = true;
        }
        // 途中で無効にしても、追跡中のものの後片付けは続ける
        if (everEnabled) {
            FiberCrosstalkDetector.onTickEnd();
        }
    }
}
