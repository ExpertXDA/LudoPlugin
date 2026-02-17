package ru.ludomania;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class LudoPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final NamespacedKey BARREL_KEY = new NamespacedKey(this, "ludo_barrel");
    private final NamespacedKey PLACER_KEY = new NamespacedKey(this, "placer_uuid");

    private final Map<UUID, LudoData> addicts = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();
    private final Set<String> barrelLocations = ConcurrentHashMap.newKeySet();

    private final List<String> MESSAGES = List.of(
            "РУКИ ТРЯСУТСЯ...", "ГДЕ АРЫ?!", "МНЕ НУЖНО ЕЩЕ...",
            "ВСЕГО ОДИН СТАК...", "Я ОТЫГРАЮСЬ, ОБЕЩАЮ...", "ГОЛОСА В ГОЛОВЕ...",
            "ПРОДАЙ ПОЧКУ - ДЕПНИ В БОЧКУ!", "БОЛЬ... ТОЛЬКО ДОДЕП СПАСЕТ..."
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        registerRecipe();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ludo").setExecutor(this);

        // Партиклы
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, (task) -> {
            for (String locStr : barrelLocations) {
                Location loc = strToLoc(locStr);
                if (loc != null) {
                    Bukkit.getRegionScheduler().execute(this, loc, () -> {
                        if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                            loc.getWorld().spawnParticle(Particle.ENCHANT, loc.clone().add(0.5, 1.2, 0.5), 5, 0.3, 0.5, 0.3, 0.05);
                        }
                    });
                }
            }
        }, 40L, 40L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (addicts.containsKey(p.getUniqueId())) startLudoTask(p);
        }
    }

    @Override
    public void onDisable() {
        playerTasks.values().forEach(ScheduledTask::cancel);
        saveAllData();
    }

    // --- ЛОГИКА ДЕПОЗИТА ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        Inventory clickedInv = e.getClickedInventory();
        Inventory topInv = e.getView().getTopInventory();

        if (!(topInv.getHolder() instanceof Barrel barrel) || !isLudoBarrel(barrel)) return;

        if (clickedInv == topInv) {
            ItemStack cursor = e.getCursor();
            if (isValidOre(cursor)) {
                if (processDeposit(player, barrel, cursor)) {
                    e.setCursor(null);
                    e.setCancelled(true);
                } else {
                    e.setCancelled(true);
                }
            }
        }
        else if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack current = e.getCurrentItem();
            if (isValidOre(current)) {
                if (processDeposit(player, barrel, current)) {
                    e.setCurrentItem(null);
                    e.setCancelled(true);
                } else {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (e.getInventory().getHolder() instanceof Container hopper) {
            Item itemEntity = e.getItem();
            ItemStack stack = itemEntity.getItemStack();

            if (!isValidOre(stack)) return;

            if (hopper.getBlock().getBlockData() instanceof Hopper hopperData) {
                Block targetBlock = hopper.getBlock().getRelative(hopperData.getFacing());
                if (targetBlock.getState() instanceof Barrel barrel && isLudoBarrel(barrel)) {
                    UUID throwerUUID = itemEntity.getThrower();
                    if (throwerUUID != null) {
                        Player thrower = Bukkit.getPlayer(throwerUUID);
                        if (thrower != null) {
                            if (processDeposit(thrower, barrel, stack)) {
                                e.setCancelled(true);
                                itemEntity.remove();
                            } else {
                                e.setCancelled(true);
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ГЛАВНАЯ ФУНКЦИЯ ДЕПОЗИТА ---

    private boolean processDeposit(Player player, Barrel barrel, ItemStack stack) {
        String ownerUUID = barrel.getPersistentDataContainer().get(PLACER_KEY, PersistentDataType.STRING);
        if (ownerUUID != null && ownerUUID.equals(player.getUniqueId().toString())) {
            player.sendMessage(Component.text("Нельзя играть в своем казино!", NamedTextColor.RED));
            return false;
        }

        Block bankBlock = barrel.getBlock().getRelative(BlockFace.DOWN);
        if (bankBlock.getType() != Material.HOPPER) {
            player.sendMessage(Component.text("Казино сломано (нет воронки снизу)!", NamedTextColor.RED));
            return false;
        }

        org.bukkit.block.Hopper bankHopper = (org.bukkit.block.Hopper) bankBlock.getState();

        ItemStack toAdd = stack.clone();
        HashMap<Integer, ItemStack> leftOver = bankHopper.getInventory().addItem(toAdd);

        if (!leftOver.isEmpty()) {
            player.sendMessage(Component.text("КАССА ПЕРЕПОЛНЕНА!", NamedTextColor.RED, TextDecoration.BOLD));
            return false;
        }

        int amount = stack.getAmount();
        LudoData data = addicts.getOrDefault(player.getUniqueId(), new LudoData());

        long currentTime = System.currentTimeMillis();
        boolean isAddicted = playerTasks.containsKey(player.getUniqueId()) || data.percentage > 0;

        if (!isAddicted) {
            // Если с последнего депа прошло больше 10 минут (600 сек * 1000)
            if (currentTime - data.lastDepositTime > 600000) {
                data.totalDeposited = 0;
            }
        }

        data.totalDeposited += amount;
        data.lastDepositTime = currentTime;

        // ПОРОГ ВХОДА: 10 РУДЫ
        if (data.totalDeposited >= 10) {
            data.percentage = 100.0;
            addicts.put(player.getUniqueId(), data);

            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
            player.showTitle(Title.title(
                    Component.text("ДЕПНУТО: " + amount, NamedTextColor.GREEN),
                    Component.text("ВСЕГО: " + data.totalDeposited, NamedTextColor.AQUA)
            ));

            if (!playerTasks.containsKey(player.getUniqueId())) startLudoTask(player);
        } else {
            addicts.put(player.getUniqueId(), data);
            player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 2.0f);
            player.sendActionBar(Component.text("Депозит принят (" + data.totalDeposited + "/10 для активации)", NamedTextColor.GRAY));
        }

        return true;
    }

    private boolean isValidOre(ItemStack item) {
        return item != null && item.getType() == Material.DEEPSLATE_DIAMOND_ORE;
    }

    private boolean isLudoBarrel(Barrel b) {
        return b.getPersistentDataContainer().has(BARREL_KEY, PersistentDataType.BYTE);
    }

    // --- ЛОГИКА ЛОМКИ И ЭФФЕКТОВ ---

    private void startLudoTask(Player player) {
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, (t) -> {
            UUID uid = player.getUniqueId();
            if (!addicts.containsKey(uid)) {
                t.cancel();
                playerTasks.remove(uid);
                return;
            }

            LudoData data = addicts.get(uid);

            if (data.totalDeposited < 10) {
                t.cancel();
                playerTasks.remove(uid);
                return;
            }

            double decay;
            if (data.percentage > 20) {
                decay = 0.4 + (data.totalDeposited / 2000.0);
            } else {
                decay = 0.5 / (1.0 + (data.totalDeposited / 300.0));
            }

            data.percentage -= decay;

            if (data.percentage <= 0) {
                addicts.remove(uid);
                player.showTitle(Title.title(
                        Component.text("СВОБОДА..."),
                        Component.text("Ты чист"),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(1000))
                ));
                player.sendMessage(Component.text("Ты переборол зависимость.", NamedTextColor.GREEN));
                t.cancel();
                playerTasks.remove(uid);
                return;
            }

            if (data.percentage < 20) {
                applyWithdrawalEffects(player, data.totalDeposited);
            }

        }, null, 20L, 20L);

        playerTasks.put(player.getUniqueId(), task);
    }

    private void applyWithdrawalEffects(Player p, int total) {
        int chance = ThreadLocalRandom.current().nextInt(100);
        int severity = total < 200 ? 0 : (total < 1000 ? 1 : 2);


        PotionEffect currentNausea = p.getPotionEffect(PotionEffectType.NAUSEA);

        if (currentNausea == null || currentNausea.getDuration() < 100) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 300, 0, false, false));
        }

        // Слепота для средних и жестких (шанс 10%)
        if (severity >= 1 && chance < 10) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false));
        }

        // Рандомные приходы (5% в секунду)
        if (chance < 5) {
            String msg = MESSAGES.get(ThreadLocalRandom.current().nextInt(MESSAGES.size()));
            p.sendMessage(Component.text(msg, NamedTextColor.DARK_RED, TextDecoration.BOLD));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);

            if (severity == 2) {
                p.damage(1.0);
                p.playSound(p.getLocation(), Sound.ENTITY_GHAST_SCREAM, 0.5f, 0.5f);
                p.showTitle(Title.title(
                        Component.text("☠", NamedTextColor.RED),
                        Component.text("БОЛЬ НЕ УЙДЕТ", NamedTextColor.DARK_RED),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1000), Duration.ofMillis(500))
                ));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 1, false, false));
            }
        }
    }

    // --- ОСТАЛЬНОЕ ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!addicts.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("Ты не лудоман.", NamedTextColor.GREEN));
            return true;
        }
        LudoData data = addicts.get(player.getUniqueId());

        if (data.totalDeposited < 10) {
            player.sendMessage(Component.text("Пока держишься... (" + data.totalDeposited + "/10)", NamedTextColor.YELLOW));
            return true;
        }

        NamedTextColor color = data.percentage > 50 ? NamedTextColor.GREEN : (data.percentage > 20 ? NamedTextColor.YELLOW : NamedTextColor.RED);
        player.sendMessage(Component.text("--- СТАТУС ---", NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text("Состояние: ", NamedTextColor.GRAY).append(Component.text(String.format("%.1f%%", data.percentage), color)));
        player.sendMessage(Component.text("Всего слито: ", NamedTextColor.GRAY).append(Component.text(data.totalDeposited, NamedTextColor.AQUA)));
        return true;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (hand.getItemMeta() != null && hand.getItemMeta().getPersistentDataContainer().has(BARREL_KEY, PersistentDataType.BYTE)) {
            Barrel barrel = (Barrel) e.getBlockPlaced().getState();
            barrel.getPersistentDataContainer().set(BARREL_KEY, PersistentDataType.BYTE, (byte)1);
            barrel.getPersistentDataContainer().set(PLACER_KEY, PersistentDataType.STRING, e.getPlayer().getUniqueId().toString());
            barrel.update();
            barrelLocations.add(locToStr(e.getBlock().getLocation()));
            saveAllData();
            e.getPlayer().sendMessage(Component.text("БОЧКА ПОСТАВЛЕНА!", NamedTextColor.GOLD));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (barrelLocations.remove(locToStr(e.getBlock().getLocation()))) saveAllData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (addicts.containsKey(e.getPlayer().getUniqueId())) {
            if (addicts.get(e.getPlayer().getUniqueId()).totalDeposited >= 10) {
                startLudoTask(e.getPlayer());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScheduledTask t = playerTasks.remove(e.getPlayer().getUniqueId());
        if (t != null) t.cancel();
        saveAllData();
    }

    private void registerRecipe() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("БОЧКА ЛУДОМАНА", NamedTextColor.LIGHT_PURPLE));
        meta.getPersistentDataContainer().set(BARREL_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "ludo_barrel_recipe"), item);
        recipe.shape("DDD", "DBD", "DDD");
        recipe.setIngredient('D', Material.DIAMOND_ORE);
        recipe.setIngredient('B', Material.BARREL);
        Bukkit.addRecipe(recipe);
    }

    private static class LudoData {
        double percentage = 0;
        int totalDeposited = 0;
        long lastDepositTime = 0;
    }

    private void loadData() {
        if (getConfig().contains("barrels")) barrelLocations.addAll(getConfig().getStringList("barrels"));
        if (getConfig().contains("players")) {
            ConfigurationSection sec = getConfig().getConfigurationSection("players");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    LudoData d = new LudoData();
                    d.percentage = sec.getDouble(k + ".pct");
                    d.totalDeposited = sec.getInt(k + ".total");
                    addicts.put(UUID.fromString(k), d);
                }
            }
        }
    }

    private void saveAllData() {
        getConfig().set("barrels", new ArrayList<>(barrelLocations));
        getConfig().set("players", null);
        addicts.forEach((uuid, data) -> {
            getConfig().set("players." + uuid + ".pct", data.percentage);
            getConfig().set("players." + uuid + ".total", data.totalDeposited);
        });
        saveConfig();
    }

    private String locToStr(Location loc) { return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ(); }

    private Location strToLoc(String str) {
        try {
            String[] p = str.split(",");
            return new Location(Bukkit.getWorld(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (Exception e) { return null; }
    }
}