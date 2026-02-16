package ru.ludomania;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class LudoPlugin extends JavaPlugin implements Listener {

    private final NamespacedKey BARREL_KEY = new NamespacedKey(this, "ludo_barrel");
    private final NamespacedKey PLACER_KEY = new NamespacedKey(this, "placer_uuid");
    private final Set<String> barrelLocations = ConcurrentHashMap.newKeySet();
    private final Set<UUID> addictedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();

        // Регистрация крафта
        registerRecipe();

        // Регистрация событий
        getServer().getPluginManager().registerEvents(this, this);

        // Запуск партиклов (Глобальный таймер, который раскидывает задачи по регионам)
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, (task) -> {
            for (String locStr : barrelLocations) {
                Location loc = strToLoc(locStr);
                if (loc != null) {
                    // Спавним партиклы в потоке региона этого блока
                    Bukkit.getRegionScheduler().execute(this, loc, () -> {
                        if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                            loc.getWorld().spawnParticle(Particle.ENCHANT, loc.clone().add(0.5, 1.2, 0.5), 10, 0.3, 0.5, 0.3, 0.05);
                        }
                    });
                }
            }
        }, 20L, 20L); // Раз в секунду

        // Восстанавливаем зависимость для онлайн игроков (после релоада)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (addictedPlayers.contains(p.getUniqueId())) {
                startAddictionTask(p);
            }
        }

        getLogger().info("ЛУДОМАНИЯ АКТИВИРОВАНА! ГОТОВЬТЕ АЛМАЗЫ!");
    }

    @Override
    public void onDisable() {
        // Отменяем все задачи
        playerTasks.values().forEach(ScheduledTask::cancel);
        playerTasks.clear();
    }

    // --- КРАФТ ---
    private void registerRecipe() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("БОЧКА ЛУДОМАНА", NamedTextColor.GOLD));
        meta.getPersistentDataContainer().set(BARREL_KEY, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Поставь меня, если смелый...", NamedTextColor.DARK_PURPLE));
        meta.lore(lore);
        item.setItemMeta(meta);

        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "ludo_barrel_recipe"), item);
        recipe.shape("DDD", "DBD", "DDD"); // D - Ore, B - Barrel
        recipe.setIngredient('D', Material.DIAMOND_ORE);
        recipe.setIngredient('B', Material.BARREL);

        Bukkit.addRecipe(recipe);
    }

    // --- СОБЫТИЯ ---

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (hand.getItemMeta() == null) return;

        // Проверка, что это наша бочка
        if (hand.getItemMeta().getPersistentDataContainer().has(BARREL_KEY, PersistentDataType.BYTE)) {
            Block b = e.getBlockPlaced();

            // Записываем UUID того, кто поставил, прямо в блок (PDC блока)
            // В Folia/Paper работа с тайлами безопасна в эвенте плейса
            if (b.getState() instanceof Barrel barrel) {
                barrel.getPersistentDataContainer().set(BARREL_KEY, PersistentDataType.BYTE, (byte)1);
                barrel.getPersistentDataContainer().set(PLACER_KEY, PersistentDataType.STRING, e.getPlayer().getUniqueId().toString());
                barrel.update();
            }

            String locStr = locToStr(b.getLocation());
            barrelLocations.add(locStr);
            saveLocs();

            e.getPlayer().sendMessage(Component.text("Ты поставил БОЧКУ ЛУДОМАНА! Жди клиентов...", NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        String locStr = locToStr(e.getBlock().getLocation());
        if (barrelLocations.contains(locStr)) {
            barrelLocations.remove(locStr);
            saveLocs();
            e.getPlayer().sendMessage(Component.text("Казино закрыто!", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getInventory().getHolder() instanceof Barrel barrel)) return;

        // Проверяем, что это Бочка Лудомана
        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        if (!pdc.has(BARREL_KEY, PersistentDataType.BYTE)) return;

        ItemStack cursor = e.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        // Логика "Депозита" (10 руды)
        if (cursor.getType() == Material.DEEPSLATE_DIAMOND_ORE && cursor.getAmount() >= 10) {

            // Проверка на воронку снизу
            Block blockUnder = barrel.getBlock().getRelative(BlockFace.DOWN);
            if (blockUnder.getType() != Material.HOPPER) {
                player.sendMessage(Component.text("Под бочкой должна быть воронка, чтобы 'сливать' депозит!", NamedTextColor.RED));
                return;
            }

            // Проверка на владельца (защита от абуза)
            String ownerUUID = pdc.get(PLACER_KEY, PersistentDataType.STRING);
            if (ownerUUID != null && ownerUUID.equals(player.getUniqueId().toString())) {
                player.sendMessage(Component.text("Нельзя играть в своем казино! (Абуз запрещен)", NamedTextColor.RED));
                return;
            }

            if (!addictedPlayers.contains(player.getUniqueId())) {
                // ЗАБИРАЕМ 10 РУДЫ
                cursor.setAmount(cursor.getAmount() - 10);
                e.setCursor(cursor);
                e.setCancelled(true); // Отменяем класть в слот, просто забираем

                // НАКЛАДЫВАЕМ ЭФФЕКТ
                addictedPlayers.add(player.getUniqueId());
                saveAddicted();
                startAddictionTask(player);

                player.showTitle(Title.title(
                        Component.text("ХОЧУ ДЕПНУТЬ!", NamedTextColor.DARK_RED),
                        Component.text("Ты подсел на азарт...", NamedTextColor.GRAY)
                ));
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.5f);

                // Мгновенные дебаффы
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1)); // 10 сек
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 600, 1)); // 30 сек
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 400, 1)); // 20 сек
            } else {
                player.sendMessage(Component.text("Ты уже лудоман! Тебе нужно лечение (стак руды)!", NamedTextColor.RED));
            }
        }

        // Логика "Лечения" (64 руды)
        if (cursor.getType() == Material.DEEPSLATE_DIAMOND_ORE && cursor.getAmount() == 64) {
            if (addictedPlayers.contains(player.getUniqueId())) {
                // ЗАБИРАЕМ СТАК
                e.setCursor(null); // Забираем все
                e.setCancelled(true);

                // ЛЕЧИМ
                addictedPlayers.remove(player.getUniqueId());
                saveAddicted();
                ScheduledTask task = playerTasks.remove(player.getUniqueId());
                if (task != null) task.cancel();

                player.showTitle(Title.title(
                        Component.text("ИЗЛЕЧЕН!", NamedTextColor.GREEN),
                        Component.text("Больше не играй...", NamedTextColor.YELLOW)
                ));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (addictedPlayers.contains(e.getPlayer().getUniqueId())) {
            startAddictionTask(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScheduledTask task = playerTasks.remove(e.getPlayer().getUniqueId());
        if (task != null) task.cancel();
    }

    // --- ЛОГИКА ЛУДОМАНИИ (EntityScheduler для Folia) ---
    private void startAddictionTask(Player player) {
        // Рандом от 3 до 5 минут (в тиках: 3600 - 6000)
        long delay = ThreadLocalRandom.current().nextLong(3600, 6001);

        // Используем планировщик сущности (EntityScheduler), так как игрок перемещается между регионами
        ScheduledTask task = player.getScheduler().runDelayed(this, (t) -> {
            if (!player.isOnline() || !addictedPlayers.contains(player.getUniqueId())) return;

            // ЭФФЕКТЫ "ЛОМКИ"
            player.showTitle(Title.title(
                    Component.text("ХОЧУ ДЕПНУТЬ!", NamedTextColor.DARK_RED),
                    Component.text("Где мой стак алмазной руды?!", NamedTextColor.RED)
            ));

            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 600, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 400, 1));

            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);

            // Перезапускаем задачу (рекурсивно)
            startAddictionTask(player);

        }, null, delay);

        playerTasks.put(player.getUniqueId(), task);
    }

    // --- УТИЛИТЫ И КОНФИГ ---
    private void loadData() {
        FileConfiguration cfg = getConfig();
        if (cfg.contains("barrels")) {
            barrelLocations.addAll(cfg.getStringList("barrels"));
        }
        if (cfg.contains("addicted")) {
            for (String s : cfg.getStringList("addicted")) {
                try {
                    addictedPlayers.add(UUID.fromString(s));
                } catch (Exception ignored) {}
            }
        }
    }

    private void saveLocs() {
        getConfig().set("barrels", new ArrayList<>(barrelLocations));
        saveConfig();
    }

    private void saveAddicted() {
        List<String> list = new ArrayList<>();
        addictedPlayers.forEach(uuid -> list.add(uuid.toString()));
        getConfig().set("addicted", list);
        saveConfig();
    }

    private String locToStr(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location strToLoc(String str) {
        try {
            String[] parts = str.split(",");
            World w = Bukkit.getWorld(parts[0]);
            if (w == null) return null;
            return new Location(w, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (Exception e) {
            return null;
        }
    }
}