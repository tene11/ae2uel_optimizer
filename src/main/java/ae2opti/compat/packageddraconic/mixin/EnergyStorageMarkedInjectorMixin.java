package ae2opti.compat.packageddraconic.mixin;

import ae2opti.p2p.ILongEnergyReceiver;
import com.brandon3055.draconicevolution.api.fusioncrafting.IFusionCraftingInventory;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import thelm.packageddraconic.energy.EnergyStorageMarkedInjector;
import thelm.packageddraconic.tile.TileMarkedInjector;

/**
 * PackagedDraconic（1.12.2-1.0.4.24）の Marked Injector の電力ストレージに、{@code long} で受け取る口を足す。
 *
 * <p>中身は元の {@code receiveEnergy(int, boolean)} と同じ計算式：
 * 1回あたり {@code min(コスト − 充電済み, max(コスト ÷ chargeRate, 1), 渡したい量)} を受け取る。
 * 1回で渡すので充電速度の上限も元どおり。違いは、渡せる量が {@code int} に縛られないことと、
 * 受け取る量を 0 以上で止めること。</p>
 */
@Mixin(value = EnergyStorageMarkedInjector.class, remap = false)
public abstract class EnergyStorageMarkedInjectorMixin implements ILongEnergyReceiver {

    @Shadow
    @Final
    public TileMarkedInjector tile;

    @Shadow
    protected long energy;

    @Override
    public long ae2opti$receiveLong(EnumFacing side, long maxReceive, boolean simulate) {
        this.tile.validateCraftingInventory();
        final IFusionCraftingInventory inv = this.tile.getCraftingInventory();
        if (inv == null) {
            return 0;
        }
        final long cost = inv.getIngredientEnergyCost();
        final long maxPerCall = Math.max(cost / this.tile.getChargeRate(), 1L);
        final long received = Math.max(0L, Math.min(cost - this.energy, Math.min(maxPerCall, maxReceive)));
        if (!simulate) {
            this.energy += received;
            this.tile.markDirty();
        }
        return received;
    }
}
