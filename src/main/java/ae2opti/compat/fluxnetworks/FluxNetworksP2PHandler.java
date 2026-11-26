package ae2opti.compat.fluxnetworks;

import ae2opti.p2p.FEP2PDistributor;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.parts.p2p.PartP2PFEPower;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import sonar.fluxnetworks.api.energy.ITileEnergyHandler;

import javax.annotation.Nonnull;

/**
 * Flux Networks（1.12.2-4.1.1.34）の受け渡し方法の1つとして、P2P FE の入口を {@code long} のまま扱う。
 *
 * <p>Flux Networks は中では {@code long} で動いているが、FE の機械へは {@code ForgeEnergyHandler.addEnergy} で
 * {@code int} の上限に切り詰めている。{@code TileEntityHandler.tileEnergyHandlers} の先頭にこれを入れると、
 * 隣が P2P FE の入口のときだけ {@code addEnergy(long)} の量をそのまま P2P の配分処理（{@link FEP2PDistributor}）に流す。
 * それ以外のタイルには何もしない（{@link #hasCapability} が false なので、次の受け渡し方法に回る）。</p>
 */
public final class FluxNetworksP2PHandler implements ITileEnergyHandler {

    public static final FluxNetworksP2PHandler INSTANCE = new FluxNetworksP2PHandler();

    private FluxNetworksP2PHandler() {
    }

    /** {@code tile} の {@code side} の面にある P2P FE の入口（Flux Networks の面の向きは capability と同じ） */
    private static PartP2PFEPower inputAt(TileEntity tile, EnumFacing side) {
        // mixin が当たっていない・p2p.newDistribution がオフなら、Flux Networks の普通の FE の受け渡しに任せる
        if (side == null || !FEP2PDistributor.isAvailable() || !(tile instanceof IPartHost) || tile.isInvalid()) {
            return null;
        }
        final IPart part = ((IPartHost) tile).getPart(side);
        if (part instanceof PartP2PFEPower && !((PartP2PFEPower) part).isOutput()) {
            return (PartP2PFEPower) part;
        }
        return null;
    }

    @Override
    public boolean hasCapability(@Nonnull TileEntity tile, EnumFacing side) {
        return inputAt(tile, side) != null;
    }

    @Override
    public boolean canAddEnergy(@Nonnull TileEntity tile, EnumFacing side) {
        return inputAt(tile, side) != null;
    }

    @Override
    public boolean canRemoveEnergy(@Nonnull TileEntity tile, EnumFacing side) {
        return false;
    }

    @Override
    public long addEnergy(long amount, @Nonnull TileEntity tile, EnumFacing side, boolean simulate) {
        final PartP2PFEPower input = inputAt(tile, side);
        return input == null ? 0 : FEP2PDistributor.distribute(input, amount, simulate);
    }

    @Override
    public long removeEnergy(long amount, @Nonnull TileEntity tile, EnumFacing side) {
        return 0;
    }
}
