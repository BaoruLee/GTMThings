package com.hepdd.gtmthings.common.block.machine.multiblock.part;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IExplosionMachine;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableLaserContainer;
import com.gregtechceu.gtceu.common.data.GTItems;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.hepdd.gtmthings.api.capability.IBindable;
import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import javax.annotation.ParametersAreNonnullByDefault;

import static com.hepdd.gtmthings.utils.TeamUtil.GetName;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class WirelessLaserHatchPartMachine extends TieredIOPartMachine implements IInteractedMachine, IBindable, IExplosionMachine, IMachineLife {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            WirelessLaserHatchPartMachine.class, TieredIOPartMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Persisted
    public UUID owner_uuid;
    @Persisted
    public final NotifiableLaserContainer energyContainer;
    @Getter
    protected int amperage;
    private TickableSubscription updEnergySubs;

    public WirelessLaserHatchPartMachine(IMachineBlockEntity holder, int tier, IO io, int amperage, Object... args) {
        super(holder, tier, io);
        this.amperage = amperage;
        this.energyContainer = createEnergyContainer(args);
    }

    protected NotifiableLaserContainer createEnergyContainer(Object... args) {
        NotifiableLaserContainer container;
        if (io == IO.OUT) {
            container = NotifiableLaserContainer.emitterContainer(this, GTValues.V[tier] * 64L * amperage,
                    GTValues.V[tier], amperage);
        } else {
            container = NotifiableLaserContainer.receiverContainer(this, GTValues.V[tier] * 64L * amperage,
                    GTValues.V[tier], amperage);
        }
        return container;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        updateEnergySubscription();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (updEnergySubs != null) {
            updEnergySubs.unsubscribe();
            updEnergySubs = null;
        }
    }

    private void updateEnergySubscription() {
        if (this.owner_uuid != null) {
            updEnergySubs = subscribeServerTick(updEnergySubs, this::updateEnergy);
        } else if (updEnergySubs != null) {
            updEnergySubs.unsubscribe();
            updEnergySubs = null;
        }
    }

    private void updateEnergy() {
        if (this.owner_uuid == null) return;
        if (io == IO.IN) {
            useEnergy();
        } else {
            addEnergy();
        }
    }

    private void useEnergy() {
        var currentStored = energyContainer.getEnergyStored();
        var maxStored = energyContainer.getEnergyCapacity();
        var changeStored = Math.min(maxStored - currentStored, energyContainer.getInputVoltage() * energyContainer.getInputAmperage());
        if (changeStored <= 0) return;
        changeStored = WirelessEnergyManager.addEUToGlobalEnergyMap(this.owner_uuid, -changeStored, this);
        if (changeStored < 0) energyContainer.setEnergyStored(currentStored - changeStored);
    }

    private void addEnergy() {
        var currentStored = energyContainer.getEnergyStored();
        if (currentStored <= 0) return;
        var changeStored = Math.min(energyContainer.getOutputVoltage() * energyContainer.getOutputAmperage(), currentStored);
        changeStored = WirelessEnergyManager.addEUToGlobalEnergyMap(this.owner_uuid, changeStored, this);
        if (changeStored > 0) energyContainer.setEnergyStored(currentStored - changeStored);
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return false;
    }

    @Override
    public InteractionResult onUse(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack is = player.getItemInHand(hand);
        if (is.isEmpty()) return InteractionResult.PASS;
        if (is.is(GTItems.TOOL_DATA_STICK.asItem())) {
            this.owner_uuid = player.getUUID();
            if (getLevel().isClientSide()) {
                player.sendSystemMessage(Component.translatable("gtmthings.machine.wireless_energy_hatch.tooltip.bind", GetName(player)));
            }
            updateEnergySubscription();
            return InteractionResult.SUCCESS;
        } else if (is.is(Items.STICK)) {
            if (io == IO.OUT) energyContainer.setEnergyStored(GTValues.V[tier] * 64L * amperage);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean onLeftClick(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction) {
        ItemStack is = player.getItemInHand(hand);
        if (is.isEmpty()) return false;
        if (is.is(GTItems.TOOL_DATA_STICK.asItem())) {
            this.owner_uuid = null;
            if (getLevel().isClientSide()) {
                player.sendSystemMessage(Component.translatable("gtmthings.machine.wireless_energy_hatch.tooltip.unbind"));
            }
            updateEnergySubscription();
            return true;
        }
        return false;
    }

    @Override
    public void onMachinePlaced(@Nullable LivingEntity player, ItemStack stack) {
        if (player != null) {
            this.owner_uuid = player.getUUID();
            updateEnergySubscription();
        }
    }

    @Override
    public UUID getUUID() {
        return this.owner_uuid;
    }

    @Override
    public void setUUID(UUID uuid) {
        this.owner_uuid = uuid;
    }

    //////////////////////////////////////
    // ********** Misc **********//
    //////////////////////////////////////

    @Override
    public int tintColor(int index) {
        if (index == 2) {
            return GTValues.VC[getTier()];
        }
        return super.tintColor(index);
    }
}
