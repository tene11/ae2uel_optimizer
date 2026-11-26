package ae2opti.compat.fluxnetworks;

import ae2opti.AE2Opti;
import sonar.fluxnetworks.common.handler.TileEntityHandler;

/**
 * Flux Networks があるときだけ呼ばれる（{@link AE2Opti} の postInit から）。
 * このクラスを読み込むと Flux Networks のクラスも読み込まれるので、Flux Networks が無いときは触らないこと。
 */
public final class FluxNetworksCompat {

    private FluxNetworksCompat() {
    }

    public static void register() {
        TileEntityHandler.tileEnergyHandlers.remove(FluxNetworksP2PHandler.INSTANCE);
        TileEntityHandler.tileEnergyHandlers.add(0, FluxNetworksP2PHandler.INSTANCE);
        AE2Opti.LOGGER.info("Registered the long FE P2P handler for Flux Networks.");
    }
}
