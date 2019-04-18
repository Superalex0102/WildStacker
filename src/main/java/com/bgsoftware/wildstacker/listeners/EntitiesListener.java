package com.bgsoftware.wildstacker.listeners;

import com.bgsoftware.wildstacker.Locale;
import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.enums.StackSplit;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.hooks.MythicMobsHook;
import com.bgsoftware.wildstacker.listeners.events.EntityBreedEvent;
import com.bgsoftware.wildstacker.listeners.plugins.EpicSpawnersListener;
import com.bgsoftware.wildstacker.objects.WStackedEntity;
import com.bgsoftware.wildstacker.utils.EntityUtil;
import com.bgsoftware.wildstacker.utils.Executor;
import com.bgsoftware.wildstacker.utils.ItemUtil;
import com.bgsoftware.wildstacker.utils.legacy.Materials;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SheepDyeWoolEvent;
import org.bukkit.event.entity.SheepRegrowWoolEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
public final class EntitiesListener implements Listener {

    private WildStackerPlugin plugin;

    public static Set<UUID> noStackEntities = new HashSet<>();

    public EntitiesListener(WildStackerPlugin plugin){
        this.plugin = plugin;
        if(plugin.getServer().getBukkitVersion().contains("1.13"))
            plugin.getServer().getPluginManager().registerEvents(new EntitiesListener1_13(), plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent e){
        if(e.getEntityType() == EntityType.ARMOR_STAND || e.getEntityType() == EntityType.PLAYER)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        //Checks if the entity should be handled here
        if(!stackedEntity.isIgnoreDeathEvent() && !e.getEntity().hasMetadata("corpse") && !e.getEntity().hasMetadata("mythic-mob"))
            return;

        //Checks if it's mythic-mob
        if(e.getEntity().hasMetadata("mythic-mob")){
            //The only way to get drops of mythic-mobs is using the event#getDrops
            stackedEntity.setTempLootTable(new ArrayList<>(e.getDrops()));

            int unstackAmount = e.getEntity().getMetadata("mythic-mob").get(0).asInt();
            EntityDamageEvent.DamageCause lastDamageCause = getLastDamage(e.getEntity());
            final int lootBonusLevel = getFortuneLevel(e.getEntity());

            //Dropping the mythic mobs loot
            calcAndDrop(stackedEntity, e.getEntity().getLocation(), lootBonusLevel, unstackAmount);
            e.getDrops().clear();
            return;
        }

        /*
        The entity is a corpse. We only need to drop the exp.
        It's done here because that's the easiest way of getting the default exp
        of the entity.
         */

        int expToDrop = -1;

        if(e.getEntity().hasMetadata("corpse")){
            try {
                expToDrop = e.getEntity().getMetadata("corpse").get(0).asInt();
            }catch(Exception ignored){}
        }

        /*
        Any number between 0 presents the default exp. The stack size is the expToDrop / (-1),
        so -8 is a stack size of 8 and so on.
         */

        e.setDroppedExp(expToDrop < 0 ? EntityUtil.getEntityExp(e.getEntity()) * (expToDrop / (-1)) : expToDrop);

        //We don't need to drop drops.
        e.getDrops().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDamageEvent e){
        //Checks that it's the last hit of the entity
        if(!(e.getEntity() instanceof LivingEntity) || ((LivingEntity) e.getEntity()).getHealth() - e.getFinalDamage() > 0)
            return;

        if(e.getEntityType() == EntityType.ARMOR_STAND || e.getEntityType() == EntityType.PLAYER)
            return;

        LivingEntity livingEntity = (LivingEntity) e.getEntity();
        StackedEntity stackedEntity = WStackedEntity.of(livingEntity);

        //Should be handled in the EntityDeathEvent
        if(stackedEntity.isIgnoreDeathEvent())
            return;

        EntityDamageEvent.DamageCause lastDamageCause = e.getCause();
        final int lootBonusLevel = getFortuneLevel(livingEntity);

        if(plugin.getSettings().entitiesStackingEnabled || stackedEntity.getStackAmount() > 1) {
            int unstackAmount = plugin.getSettings().entitiesInstantKills.contains(lastDamageCause.name()) ? stackedEntity.getStackAmount() : 1;

            if (MythicMobsHook.isMythicMob(livingEntity)) {
                livingEntity.setMetadata("mythic-mob", new FixedMetadataValue(plugin, unstackAmount));
                return;
            }

            e.setCancelled(true);
            livingEntity.setHealth(livingEntity.getMaxHealth());

            calcAndDrop(stackedEntity, e.getEntity().getLocation(), lootBonusLevel, unstackAmount);

            Executor.sync(() -> stackedEntity.tryUnstack(unstackAmount));

            if(livingEntity.getKiller() != null) {
                try {
                    livingEntity.getKiller().incrementStatistic(Statistic.MOB_KILLS, unstackAmount);
                    livingEntity.getKiller().incrementStatistic(Statistic.KILL_ENTITY, stackedEntity.getType(), unstackAmount);
                }catch(IllegalArgumentException ignored){}
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(CreatureSpawnEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled)
            return;

        if(e.getEntityType() == EntityType.ARMOR_STAND || e.getEntityType() == EntityType.PLAYER || e.getEntity().hasMetadata("corpse"))
            return;

        if(plugin.getSettings().blacklistedEntities.contains(e.getEntityType().name()) ||
                plugin.getSettings().blacklistedEntitiesSpawnReasons.contains(e.getSpawnReason().name()))
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        stackedEntity.setSpawnReason(e.getSpawnReason());

        if(noStackEntities.contains(e.getEntity().getUniqueId())) {
            noStackEntities.remove(e.getEntity().getUniqueId());
            return;
        }

        //EpicSpawners has it's own event
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER &&
                Bukkit.getPluginManager().isPluginEnabled("EpicSpawners") && EpicSpawnersListener.isEnabled())
            return;

        if (!Bukkit.getPluginManager().isPluginEnabled("MergedSpawner") &&
                plugin.getSettings().linkedEntitiesEnabled && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER)
            return;

        //Need to add a delay so eggs will get removed from inventory
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)
            Executor.sync(stackedEntity::tryStack, 1L);
        else
            stackedEntity.tryStack();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerEggUse(PlayerInteractEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || e.getItem() == null || e.getAction() != Action.RIGHT_CLICK_BLOCK ||
                plugin.getSettings().blacklistedEntitiesSpawnReasons.contains("SPAWNER_EGG") || !Materials.isValidAndSpawnEgg(e.getItem()))
            return;

        int eggAmount = ItemUtil.getSpawnerItemAmount(e.getItem());

        if(eggAmount > 1){
            EntityType entityType = ItemUtil.getEntityType(e.getItem());

            Block spawnBlock = e.getClickedBlock().getRelative(e.getBlockFace());

            StackedEntity stackedEntity = WStackedEntity.of(
                    plugin.getSystemManager().spawnEntityWithoutStacking(spawnBlock.getLocation().add(0.5, 1, 0.5), entityType.getEntityClass(),
                            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG));

            stackedEntity.setStackAmount(eggAmount - 1, true);

            Executor.sync(stackedEntity::tryStack, 5L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent e){
        //Fixes a async catch exception with auto-clear task
        if(!Bukkit.isPrimaryThread())
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSheepDye(SheepDyeWoolEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !StackSplit.SHEEP_DYE.isEnabled())
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        int amount = stackedEntity.getStackAmount();

        DyeColor originalColor = e.getEntity().getColor();

        Executor.sync(() -> {
            if(amount > 1) {
                e.getEntity().setColor(originalColor);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicate = stackedEntity.spawnDuplicate(1);
                ((Sheep) duplicate.getLivingEntity()).setColor(e.getColor());
                duplicate.tryStack();
            }else{
                stackedEntity.tryStack();
            }
        });
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerShear(PlayerShearEntityEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !(e.getEntity() instanceof LivingEntity))
            return;

        if(!StackSplit.MUSHROOM_SHEAR.isEnabled() && e.getEntity() instanceof MushroomCow)
            return;

        if(!StackSplit.SHEEP_SHEAR.isEnabled() && e.getEntity() instanceof Sheep)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        if(e.getEntity() instanceof MushroomCow){
            stackedEntity.spawnDuplicate(stackedEntity.getStackAmount() - 1);
            stackedEntity.remove();
        }

        else if(e.getEntity() instanceof Sheep){
            int amount = stackedEntity.getStackAmount();
            Executor.sync(() -> {
                if(amount > 1) {
                    ((Sheep) e.getEntity()).setSheared(false);
                    stackedEntity.setStackAmount(amount - 1, true);
                    StackedEntity duplicate = stackedEntity.spawnDuplicate(1);
                    ((Sheep) duplicate.getLivingEntity()).setSheared(true);
                    duplicate.tryStack();
                }else{
                    stackedEntity.tryStack();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWoolRegrow(SheepRegrowWoolEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        int amount = stackedEntity.getStackAmount();

        if(!e.getEntity().isSheared())
            return;

        Executor.sync(() -> {
            if(amount > 1){
                e.getEntity().setSheared(true);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                ((Sheep) duplicated.getLivingEntity()).setSheared(false);
                duplicated.tryStack();
            }else{
                stackedEntity.tryStack();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityNameTag(PlayerInteractAtEntityEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !(e.getRightClicked() instanceof LivingEntity) || !StackSplit.NAME_TAG.isEnabled())
            return;

        ItemStack inHand = e.getPlayer().getInventory().getItemInHand();
        StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());

        if(inHand == null || inHand.getType() != Material.NAME_TAG || !inHand.hasItemMeta() || !inHand.getItemMeta().hasDisplayName()
                || e.getRightClicked() instanceof EnderDragon || e.getRightClicked() instanceof Player)
                return;

        int amount = stackedEntity.getStackAmount();

        Executor.sync(() -> {
            if(amount > 1){
                stackedEntity.setCustomName("");
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                duplicated.setCustomName(inHand.getItemMeta().getDisplayName());
                duplicated.tryStack();
            }else{
                stackedEntity.tryStack();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityFeed(PlayerInteractAtEntityEvent e){
        if(!StackSplit.ENTITY_BREED.isEnabled())
            return;

        if(!(e.getRightClicked() instanceof Animals))
            return;

        Executor.sync(() -> {
            if(!plugin.getNMSAdapter().isInLove(e.getRightClicked()))
                return;

            StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());
            int amount = stackedEntity.getStackAmount();

            if(amount > 1){
                plugin.getNMSAdapter().setInLove(stackedEntity.getLivingEntity(), e.getPlayer(), false);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                plugin.getNMSAdapter().setInLove(duplicated.getLivingEntity(), e.getPlayer(), true);
            }
        }, 1L);
    }

    @EventHandler
    public void onEntityBreed(EntityBreedEvent e){
        if(plugin.getSettings().stackAfterBreed) {
            StackedEntity motherEntity = WStackedEntity.of(e.getMother());
            StackedEntity fatherEntity = WStackedEntity.of(e.getFather());
            if(fatherEntity.tryStackInto(motherEntity)){
                ((Animals) motherEntity.getLivingEntity()).setBreed(false);
                motherEntity.tryStack();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e){
        if(e.getEntity() instanceof Animals)
            try{
                plugin.getNMSAdapter().addCustomPathfinderGoalBreed(e.getEntity());
            }catch(UnsupportedOperationException ignored){}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent e){
        if(e.getEntity() instanceof LivingEntity) {
            StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
            if (stackedEntity.isNerfed()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityInspect(PlayerInteractAtEntityEvent e){
        if(isOffHand(e) || e.getPlayer().getItemInHand() == null || !e.getPlayer().getItemInHand().isSimilar(plugin.getSettings().inspectTool) ||
                !(e.getRightClicked() instanceof LivingEntity))
            return;

        e.setCancelled(true);

        StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());

        Locale.ENTITY_INFO_HEADER.send(e.getPlayer());
        Locale.ENTITY_INFO_UUID.send(e.getPlayer(), stackedEntity.getUniqueId());
        Locale.ENTITY_INFO_TYPE.send(e.getPlayer(), EntityUtil.getFormattedType(stackedEntity.getType().name()));
        Locale.ENTITY_INFO_AMOUNT.send(e.getPlayer(), stackedEntity.getStackAmount());
        Locale.ENTITY_INFO_SPAWN_REASON.send(e.getPlayer(), stackedEntity.getSpawnReason().name());
        Locale.ENTITY_INFO_NERFED.send(e.getPlayer(), stackedEntity.isNerfed() ? "True" : "False");
        Locale.BARREL_INFO_FOOTER.send(e.getPlayer());
    }

    private boolean isOffHand(PlayerInteractAtEntityEvent event){
        try{
            return event.getClass().getMethod("getHand").invoke(event).toString().equals("OFF_HAND");
        }catch(Throwable ex){
            return true;
        }
    }

    private void calcAndDrop(StackedEntity stackedEntity, Location location, int lootBonusLevel, int stackAmount){
        if(MythicMobsHook.isMythicMob(stackedEntity.getLivingEntity()))
            return;

        Executor.async(() -> {
            List<ItemStack> drops = new ArrayList<>(stackedEntity.getDrops(lootBonusLevel, stackAmount));
            Executor.sync(() -> drops.forEach(itemStack -> ItemUtil.dropItem(itemStack, location)));
        });
    }

    private EntityDamageEvent.DamageCause getLastDamage(LivingEntity livingEntity){
        return livingEntity.getLastDamageCause() == null ? EntityDamageEvent.DamageCause.CUSTOM : livingEntity.getLastDamageCause().getCause();
    }

    private int getFortuneLevel(LivingEntity livingEntity){
        int fortuneLevel = 0;

        if(getLastDamage(livingEntity) == EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
                livingEntity.getKiller() != null && livingEntity.getKiller().getItemInHand() != null){
            fortuneLevel = livingEntity.getKiller().getItemInHand().getEnchantmentLevel(Enchantment.LOOT_BONUS_MOBS);
        }

        return fortuneLevel;
    }

    class EntitiesListener1_13 implements Listener {

        @EventHandler
        public void onEntityTransform(EntityTransformEvent e){
            StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

            if(stackedEntity.getStackAmount() > 1){
                StackedEntity transformed = WStackedEntity.of(e.getTransformedEntity());
                transformed.setStackAmount(stackedEntity.getStackAmount(), true);
                stackedEntity.remove();
            }
        }

    }

}
