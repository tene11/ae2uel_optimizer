package ae2opti.compat.draconic.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.p2p.FEP2PDistributor;
import ae2opti.p2p.ILongEnergyReceiver;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.parts.p2p.PartP2PFEPower;
import com.brandon3055.brandonscore.blocks.TileEnergyBase;
import com.brandon3055.brandonscore.lib.datamanager.ManagedBool;
import com.brandon3055.brandonscore.lib.datamanager.ManagedByte;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyPylon;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyStorageCore;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DE（1.12.2-2.3.28）のエネルギーコアのパイロン。
 *
 * <h3>入力モード：{@code long} で受け取る口（P2P FE の出口 → パイロン）</h3>
 * <p>元の {@code receiveEnergy(EnumFacing, int, boolean)} → {@code TileEnergyStorageCore.receiveEnergy(int, boolean)} と
 * 同じ条件・計算式（コアの空き容量のぶんだけ受け取る）を {@code long} で行う。パーティクルの量も元と同じ式。</p>
 *
 * <h3>出力モード：隣の P2P FE の入口へ {@code long} で渡す（{@code p2p.draconicPylonLongOutput}）</h3>
 * <p>元の {@code update()} は {@code TileEnergyBase.sendEnergyToAll(world, pos, getEnergyStored(null))} で
 * 6方向に {@code int}（コアの量を int の上限で切ったもの）を配り、配れた分をコアから引く。
 * これを包み、隣が P2P FE の入口の方向だけ、コアの {@code long} の量から直接 P2P に渡して、
 * 渡した分をコアの {@code energy} から引く。ほかの方向は元と同じ {@code TileEnergyBase.sendEnergyTo} で配り、
 * その合計を元の処理に返す（元の処理がそれをコアから引く）。</p>
 */
@Mixin(value = TileEnergyPylon.class, remap = false)
public abstract class TileEnergyPylonMixin implements ILongEnergyReceiver {

    @Shadow
    @Final
    public ManagedBool isOutputMode;

    @Shadow
    @Final
    private ManagedBool hasCoreLock;

    @Shadow
    @Final
    private ManagedByte particleRate;

    @Shadow
    private TileEnergyStorageCore getCore() {
        throw new AssertionError();
    }

    // ---- 入力モード ----

    @Override
    public long ae2opti$receiveLong(EnumFacing side, long maxReceive, boolean simulate) {
        final TileEnergyStorageCore core = this.getCore();
        if (!this.hasCoreLock.value || this.isOutputMode.value || core == null || !core.active.value) {
            return 0;
        }
        if (core.getWorld() == null || core.getWorld().isRemote) {
            return 0;
        }
        final long received = Math.max(0L, Math.min(core.getExtendedCapacity() - core.energy.value, maxReceive));
        if (!simulate) {
            core.energy.value += received;
            core.markDirty();
            if (received > 0) {
                this.particleRate.value = (byte) Math.min(20L, received < 500 ? 1L : received / 500);
            }
        }
        return received;
    }

    // ---- 出力モード ----

    // 要ローカル検証: update() は MC の ITickable の上書きなので、開発環境では "update"、本番では "func_73660_a"
    @WrapOperation(method = {"update", "func_73660_a"},
            at = @At(value = "INVOKE",
                    target = "Lcom/brandon3055/brandonscore/blocks/TileEnergyBase;sendEnergyToAll(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;I)I"))
    private int ae2opti$sendToP2PAsLong(IBlockAccess world, BlockPos pos, int maxSend, Operation<Integer> original) {
        if (!AE2OptiConfig.p2p.draconicPylonLongOutput || !(world instanceof World) || ((World) world).isRemote) {
            return original.call(world, pos, maxSend);
        }
        final TileEnergyStorageCore core = this.getCore();
        if (core == null || !ae2opti$hasP2PInputNeighbor(world, pos)) {
            return original.call(world, pos, maxSend);
        }

        int sentAsInt = 0;
        for (EnumFacing dir : EnumFacing.VALUES) {
            final PartP2PFEPower input = ae2opti$p2pInputAt(world, pos, dir);
            if (input != null) {
                // int で配った分はまだコアから引かれていない（元の処理が最後に引く）ので、その分は残しておく
                final long available = core.energy.value - sentAsInt;
                if (available > 0) {
                    final long sent = FEP2PDistributor.distribute(input, available, false);
                    if (sent > 0) {
                        core.energy.value -= sent;
                        core.markDirty();
                    }
                }
                continue;
            }
            // 元の sendEnergyToAll と同じ（残りの量を、この方向に int で配る）。ただしコアの残りを超えないようにする
            final long budget = Math.min((long) maxSend - sentAsInt, core.energy.value - sentAsInt);
            if (budget > 0) {
                sentAsInt += TileEnergyBase.sendEnergyTo(world, pos, (int) budget, dir);
            }
        }
        return sentAsInt;
    }

    @Unique
    private static boolean ae2opti$hasP2PInputNeighbor(IBlockAccess world, BlockPos pos) {
        for (EnumFacing dir : EnumFacing.VALUES) {
            if (ae2opti$p2pInputAt(world, pos, dir) != null) {
                return true;
            }
        }
        return false;
    }

    /** {@code pos} から {@code dir} の方向の隣に、こちらを向いた P2P FE の入口があれば返す */
    @Unique
    private static PartP2PFEPower ae2opti$p2pInputAt(IBlockAccess world, BlockPos pos, EnumFacing dir) {
        final TileEntity te = world.getTileEntity(pos.offset(dir));
        if (!(te instanceof IPartHost)) {
            return null;
        }
        final IPart part = ((IPartHost) te).getPart(dir.getOpposite());
        if (part instanceof PartP2PFEPower && !((PartP2PFEPower) part).isOutput()) {
            return (PartP2PFEPower) part;
        }
        return null;
    }
}
