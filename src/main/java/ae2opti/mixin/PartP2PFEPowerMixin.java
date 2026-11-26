package ae2opti.mixin;

import ae2opti.AE2OptiConfig;
import ae2opti.p2p.FEP2PState;
import appeng.parts.p2p.PartP2PFEPower;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * P2P FE のパーツ本体への mixin。
 *
 * <h3>[P-1] 出口の先の隣のブロックを、同じ tick の間だけ覚えて使い回す</h3>
 * <p>元の {@code getAttachedEnergyStorage()} は、呼ばれるたびに
 * {@code World.getTileEntity} → {@code hasCapability} → {@code getCapability} をする。
 * 入口に電力が来るたびに（シミュレート・本番・2周目と）何度も呼ばれるので、同じ tick の中ではこの3つの結果を
 * 使い回す。tick（{@code World.getTotalWorldTime()}）が変わったとき、覚えていた TileEntity が無効
 * （{@code isInvalid()}＝壊された・置き換えられた）になったとき、設定 {@code p2p.cacheAttachedStorage} が
 * オフのときは、元どおり毎回探す。隣が無い（null）ときは覚えずに毎回探す。
 * 同じ tick の途中で、TileEntity を置き換えずに capability だけを変える Mod があった場合だけ、
 * その tick の残りは古い結果を使う。{@code isActive()} の判定は元の処理のまま毎回行う。</p>
 *
 * <h3>{@link FEP2PState}</h3>
 * <p>{@link ae2opti.p2p.FEP2PDistributor} が使う状態（出口一覧のキャッシュ、配っている最中か、
 * この tick に断った量、トンネル税の上限の集計）と、出口の先を取り出す入口を足す。</p>
 */
@Mixin(value = PartP2PFEPower.class, remap = false)
public abstract class PartP2PFEPowerMixin implements FEP2PState {

    @Shadow
    private IEnergyStorage getAttachedEnergyStorage() {
        throw new AssertionError();
    }

    // ---- [P-1] ----

    /** 覚えている隣の TileEntity。null = 覚えていない */
    @Unique
    private TileEntity ae2opti$tile;

    /** {@link #ae2opti$tile} を探した tick */
    @Unique
    private long ae2opti$tick = Long.MIN_VALUE;

    /** hasCapability の結果。0 = まだ聞いていない、1 = ある、2 = ない */
    @Unique
    private byte ae2opti$hasCap;

    /** getCapability の結果。{@link #ae2opti$hasStorage} が true のときだけ有効 */
    @Unique
    private Object ae2opti$storage;

    @Unique
    private boolean ae2opti$hasStorage;

    // ---- FEP2PState ----

    /** {@link #ae2opti$rejectedAmount} を記録した tick */
    @Unique
    private long ae2opti$rejectedTick = Long.MIN_VALUE;

    /** その tick に断った（0 を返した）量のうち、いちばん大きいもの */
    @Unique
    private long ae2opti$rejectedAmount;

    @Unique
    private List<PartP2PFEPower> ae2opti$outputs;

    @Unique
    private long ae2opti$outputsTick = Long.MIN_VALUE;

    @Unique
    private boolean ae2opti$distributing;

    @Unique
    private long ae2opti$taxTick = Long.MIN_VALUE;

    @Unique
    private double ae2opti$taxPaid;

    // 要ローカル検証: World.getTileEntity は MC のメソッドなので remap = true（refmap で func_175625_s に変換される）
    @WrapOperation(method = "getAttachedEnergyStorage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getTileEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/tileentity/TileEntity;",
                    remap = true))
    private TileEntity ae2opti$cachedTile(World world, BlockPos pos, Operation<TileEntity> original) {
        if (!AE2OptiConfig.p2p.cacheAttachedStorage) {
            this.ae2opti$tile = null;
            return original.call(world, pos);
        }
        final TileEntity cached = this.ae2opti$validCachedTile(world);
        if (cached != null) {
            return cached;
        }
        final TileEntity te = original.call(world, pos);
        this.ae2opti$remember(te, world);
        return te;
    }

    @WrapOperation(method = "getAttachedEnergyStorage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/tileentity/TileEntity;hasCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/util/EnumFacing;)Z"))
    private boolean ae2opti$cachedHasCapability(TileEntity te, Capability<?> capability, EnumFacing facing,
                                                Operation<Boolean> original) {
        final boolean useCache = AE2OptiConfig.p2p.cacheAttachedStorage && te == this.ae2opti$tile;
        if (useCache && this.ae2opti$hasCap != 0) {
            return this.ae2opti$hasCap == 1;
        }
        final boolean result = original.call(te, capability, facing);
        if (useCache) {
            this.ae2opti$hasCap = result ? (byte) 1 : (byte) 2;
        }
        return result;
    }

    @WrapOperation(method = "getAttachedEnergyStorage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/tileentity/TileEntity;getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/util/EnumFacing;)Ljava/lang/Object;"))
    private Object ae2opti$cachedGetCapability(TileEntity te, Capability<?> capability, EnumFacing facing,
                                               Operation<Object> original) {
        final boolean useCache = AE2OptiConfig.p2p.cacheAttachedStorage && te == this.ae2opti$tile;
        if (useCache && this.ae2opti$hasStorage) {
            return this.ae2opti$storage;
        }
        final Object result = original.call(te, capability, facing);
        if (useCache) {
            this.ae2opti$storage = result;
            this.ae2opti$hasStorage = true;
        }
        return result;
    }

    /** この tick に覚えた、まだ有効な TileEntity。無ければ null */
    @Unique
    private TileEntity ae2opti$validCachedTile(World world) {
        final TileEntity cached = this.ae2opti$tile;
        if (cached != null && this.ae2opti$tick == world.getTotalWorldTime() && !cached.isInvalid()) {
            return cached;
        }
        return null;
    }

    @Unique
    private void ae2opti$remember(TileEntity te, World world) {
        this.ae2opti$tile = te;
        this.ae2opti$tick = world.getTotalWorldTime();
        this.ae2opti$hasCap = 0;
        this.ae2opti$storage = null;
        this.ae2opti$hasStorage = false;
    }

    // ---- FEP2PState ----

    @Override
    public IEnergyStorage ae2opti$getAttachedStorage() {
        return this.getAttachedEnergyStorage();
    }

    @Override
    public TileEntity ae2opti$getAttachedTile() {
        final PartP2PFEPower self = (PartP2PFEPower) (Object) this;
        if (!self.isActive()) {
            return null; // 元の getAttachedEnergyStorage と同じ条件
        }
        final TileEntity host = self.getTile();
        if (host == null || host.getWorld() == null) {
            return null;
        }
        final World world = host.getWorld();
        final boolean useCache = AE2OptiConfig.p2p.cacheAttachedStorage;
        if (useCache) {
            final TileEntity cached = this.ae2opti$validCachedTile(world);
            if (cached != null) {
                return cached;
            }
        }
        final TileEntity te = world.getTileEntity(host.getPos().offset(self.getSide().getFacing()));
        if (useCache) {
            this.ae2opti$remember(te, world);
        }
        return te;
    }

    @Override
    public long ae2opti$getRejected(long tick) {
        return this.ae2opti$rejectedTick == tick ? this.ae2opti$rejectedAmount : 0L;
    }

    @Override
    public void ae2opti$setRejected(long tick, long amount) {
        if (this.ae2opti$rejectedTick != tick) {
            this.ae2opti$rejectedTick = tick;
            this.ae2opti$rejectedAmount = amount;
        } else if (amount > this.ae2opti$rejectedAmount) {
            this.ae2opti$rejectedAmount = amount;
        }
    }

    @Override
    public List<PartP2PFEPower> ae2opti$getCachedOutputs(long tick) {
        return this.ae2opti$outputsTick == tick ? this.ae2opti$outputs : null;
    }

    @Override
    public void ae2opti$setCachedOutputs(List<PartP2PFEPower> outputs, long tick) {
        this.ae2opti$outputs = outputs;
        this.ae2opti$outputsTick = tick;
    }

    @Override
    public boolean ae2opti$isDistributing() {
        return this.ae2opti$distributing;
    }

    @Override
    public void ae2opti$setDistributing(boolean distributing) {
        this.ae2opti$distributing = distributing;
    }

    @Override
    public long ae2opti$getTaxTick() {
        return this.ae2opti$taxTick;
    }

    @Override
    public double ae2opti$getTaxPaid() {
        return this.ae2opti$taxPaid;
    }

    @Override
    public void ae2opti$setTaxPaid(long tick, double paid) {
        this.ae2opti$taxTick = tick;
        this.ae2opti$taxPaid = paid;
    }
}
