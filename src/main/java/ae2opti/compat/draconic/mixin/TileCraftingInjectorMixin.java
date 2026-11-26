package ae2opti.compat.draconic.mixin;

import ae2opti.p2p.ILongEnergyReceiver;
import com.brandon3055.brandonscore.blocks.TileBCBase;
import com.brandon3055.brandonscore.lib.datamanager.ManagedLong;
import com.brandon3055.draconicevolution.api.fusioncrafting.IFusionCraftingInventory;
import com.brandon3055.draconicevolution.blocks.tileentity.TileCraftingInjector;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * DE（1.12.2-2.3.28）のフュージョン用インジェクターに、{@code long} で受け取る口を足す。
 *
 * <p>中身は元の {@code receiveEnergy(EnumFacing, int, boolean)} と同じ計算式：
 * 1回あたり {@code min(渡したい量, コスト − 充電済み, コスト ÷ chargeSpeedModifier)} を受け取る
 * （chargeSpeedModifier = 300 − 段階×80）。1回で渡すので、DE が想定する充電速度の上限も元どおり守られる。
 * 違いは、渡せる量が {@code int} に縛られないことと、受け取る量を 0 以上で止めること
 * （元はコストより多く充電済みのとき、マイナスを受け取って充電が減ることがあった）。</p>
 */
@Mixin(value = TileCraftingInjector.class, remap = false)
public abstract class TileCraftingInjectorMixin implements ILongEnergyReceiver {

    @Shadow
    @Final
    private ManagedLong energy;

    @Shadow
    public IFusionCraftingInventory currentCraftingInventory;

    @Shadow
    private int chargeSpeedModifier;

    @Shadow
    private boolean validateCraftingInventory() {
        throw new AssertionError();
    }

    @Override
    public long ae2opti$receiveLong(EnumFacing side, long maxReceive, boolean simulate) {
        this.validateCraftingInventory();
        final IFusionCraftingInventory inv = this.currentCraftingInventory;
        if (inv == null) {
            return 0;
        }
        final long cost = inv.getIngredientEnergyCost();
        final long maxPerCall = cost / this.chargeSpeedModifier;
        final long accepted = Math.max(0L, Math.min(maxReceive, Math.min(cost - this.energy.value, maxPerCall)));
        if (!simulate) {
            this.energy.value += accepted;
        }
        // 元の super.update()（TileBCBase.update：データの同期）。TileCraftingInjector は上書きしていない
        ((TileBCBase) (Object) this).update();
        return accepted;
    }
}
