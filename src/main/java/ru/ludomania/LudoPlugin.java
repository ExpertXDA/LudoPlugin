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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class LudoPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final NamespacedKey BARREL_KEY = new NamespacedKey(this, "ludo_barrel");
    private final NamespacedKey PLACER_KEY = new NamespacedKey(this, "placer_uuid");

    // Храним локации бочек для партиклов
    private final Set<String> barrelLocations = ConcurrentHashMap.newKeySet();

    // Данные игроков: UUID -> Объект с инфой (процент, сколько депнул)
    private final Map<UUID, LudoData> addicts = new ConcurrentHashMap<>();

    // Активные задачи (чтобы не спамить тасками)
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();

    // Настройки баланса
    private final double DECAY_PER_SECOND = 0.4; // Сколько процентов уходит в секунду (примерно 4 минуты до ломки)

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        registerRecipe();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ludo").setExecutor(this);

        // Партиклы для бочек (Глобальный шедулер раздает задачи регионам)
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, (task) -> {
            for (String locStr : barrelLocations) {
                Location loc = strToLoc(locStr);
                if (loc != null) {
                    Bukkit.getRegionScheduler().execute(this, loc, () -> {
                        if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                            loc.getWorld().spawnParticle(Particle.ENCHANT, loc.clone().add(0.5, 1.2, 0.5), 10, 0.3, 0.5, 0.3, 0.05);
                            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0.5, 0.5, 0.5), 2, 0.4, 0.4, 0.4, 0.02);
                        }
                    });
                }
            }
        }, 20L, 20L);

        // Перезапуск задач для онлайн игроков после релоада
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (addicts.containsKey(p.getUniqueId())) {
                startLudoTask(p);
            }
        }

        getLogger().info("LUDOMANIA v2: МЕХАНИКА ЛОМКИ ЗАГРУЖЕНА!");
    }

    @Override
    public void onDisable() {
        playerTasks.values().forEach(ScheduledTask::cancel);
        playerTasks.clear();
        saveAllData();
    }

    // --- КОМАНДА /LUDO ---
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (!addicts.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("Ты абсолютно чист! Не начинай...", NamedTextColor.GREEN));
            return true;
        }

        LudoData data = addicts.get(player.getUniqueId());
        NamedTextColor color = data.percentage > 50 ? NamedTextColor.GREEN : (data.percentage > 20 ? NamedTextColor.YELLOW : NamedTextColor.RED);

        player.sendMessage(Component.text("===== СТАТУС ЛУДОМАНА =====", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Кайф: ", NamedTextColor.GRAY).append(Component.text(String.format("%.1f%%", data.percentage), color)));
        player.sendMessage(Component.text("Слито в бочку: ", NamedTextColor.GRAY).append(Component.text(data.totalDeposited + " шт.", NamedTextColor.AQUA)));

        if (data.percentage < 20) {
            player.sendMessage(Component.text("ТЕБЕ СРОЧНО НУЖНО ДЕПНУТЬ!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        } else {
            player.sendMessage(Component.text("Пока держишься...", NamedTextColor.GRAY));
        }
        return true;
    }

    // --- СОБЫТИЯ ---

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (hand.getItemMeta() != null && hand.getItemMeta().getPersistentDataContainer().has(BARREL_KEY, PersistentDataType.BYTE)) {
            Block b = e.getBlockPlaced();
            if (b.getState() instanceof Barrel barrel) {
                barrel.getPersistentDataContainer().set(BARREL_KEY, PersistentDataType.BYTE, (byte)1);
                barrel.getPersistentDataContainer().set(PLACER_KEY, PersistentDataType.STRING, e.getPlayer().getUniqueId().toString());
                barrel.update();
            }
            barrelLocations.add(locToStr(b.getLocation()));
            saveAllData();
            e.getPlayer().sendMessage(Component.text("КАЗИНО ОТКРЫТО! (Бочка установлена)", NamedTextColor.GOLD));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        String locStr = locToStr(e.getBlock().getLocation());
        if (barrelLocations.contains(locStr)) {
            barrelLocations.remove(locStr);
            saveAllData();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getInventory().getHolder() instanceof Barrel barrel)) return;

        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        if (!pdc.has(BARREL_KEY, PersistentDataType.BYTE)) return;

        ItemStack cursor = e.getCursor();
        if (cursor == null || cursor.getType() != Material.DEEPSLATE_DIAMOND_ORE) return;

        // ЛОГИКА ДЕПОЗИТА
        if (cursor.getAmount() >= 10) {
            Block blockUnder = barrel.getBlock().getRelative(BlockFace.DOWN);
            if (blockUnder.getType() != Material.HOPPER) {
                player.sendMessage(Component.text("Нужна воронка снизу для слива!", NamedTextColor.RED));
                return;
            }

            String ownerUUID = pdc.get(PLACER_KEY, PersistentDataType.STRING);
            if (ownerUUID != null && ownerUUID.equals(player.getUniqueId().toString())) {
                player.sendMessage(Component.text("В свою бочку депать нельзя! Ищи другого дилера!", NamedTextColor.RED));
                return;
            }

            // ЗАБИРАЕМ ВСЕ, ЧТО В РУКЕ (или минимум 10, но логика "депнуть всё" веселее)
            int amount = cursor.getAmount();
            e.setCursor(null); // Забираем предмет
            e.setCancelled(true);

            // ОБНОВЛЯЕМ ДАННЫЕ
            LudoData data = addicts.getOrDefault(player.getUniqueId(), new LudoData());

            // Если он был чист (percentage <= 0), сбрасываем счетчик депозитов новой сессии
            if (data.percentage <= 0) {
                data.totalDeposited = 0;
            }

            data.totalDeposited += amount;
            data.percentage = 100.0; // Фуловый кайф
            addicts.put(player.getUniqueId(), data);

            // ВИЗУАЛ
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
            player.showTitle(Title.title(
                    Component.text("ДЕПНУЛ!", NamedTextColor.GREEN),
                    Component.text("Кайф восстановлен на 100%", NamedTextColor.AQUA)
            ));

            // Запускаем таск, если его не было
            if (!playerTasks.containsKey(player.getUniqueId())) {
                startLudoTask(player);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (addicts.containsKey(e.getPlayer().getUniqueId())) {
            startLudoTask(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScheduledTask task = playerTasks.remove(e.getPlayer().getUniqueId());
        if (task != null) task.cancel();
        saveAllData(); // Сохраняем прогресс (или регресс)
    }

    // --- ЛОГИКА ЛОМКИ (EntityScheduler) ---
    private void startLudoTask(Player player) {
        // Запускаем повторяющуюся задачу раз в секунду (20 тиков)
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, (t) -> {
            UUID uid = player.getUniqueId();
            if (!addicts.containsKey(uid)) {
                t.cancel();
                playerTasks.remove(uid);
                return;
            }

            LudoData data = addicts.get(uid);

            // Снижаем процент
            data.percentage -= DECAY_PER_SECOND;

            // 1. СТАДИЯ: ИСЦЕЛЕНИЕ
            if (data.percentage <= 0) {
                addicts.remove(uid);
                player.sendMessage(Component.text("Ты переборол ломку! Ты свободен...", NamedTextColor.GREEN, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                player.resetTitle();
                t.cancel();
                playerTasks.remove(uid);
                return;
            }

            // 2. СТАДИЯ: ЛОМКА (Меньше 20%)
            if (data.percentage < 20) {
                player.sendActionBar(Component.text("ЛОМКА: " + String.format("%.1f%%", data.percentage), NamedTextColor.DARK_RED));

                // Эффекты
                if (data.percentage < 10) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, false));
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, false, false));

                // Звуки и сообщения (рандомно)
                if (ThreadLocalRandom.current().nextInt(100) < 5) {
                    player.sendMessage(Component.text("РУКИ ТРЯСУТСЯ... НУЖНО ДЕПНУТЬ...", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.5f);
                }
            }
            // 3. СТАДИЯ: НОРМА/КАЙФ (Больше 20%)
            else {
                player.sendActionBar(Component.text("Состояние: " + String.format("%.1f%%", data.percentage), NamedTextColor.GREEN));
                if (data.percentage > 80) {
                    // Бонус за свежий деп
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0, false, false));
                }
            }

        }, null, 20L, 20L); // Delay 1s, Period 1s

        playerTasks.put(player.getUniqueId(), task);
    }

    // --- КРАФТ ---
    private void registerRecipe() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("БОЧКА ЛУДОМАНА", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.getPersistentDataContainer().set(BARREL_KEY, PersistentDataType.BYTE, (byte) 1);
        meta.lore(List.of(Component.text("Поставь и жди клиентов...", NamedTextColor.GRAY)));
        item.setItemMeta(meta);

        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "ludo_barrel_recipe"), item);
        recipe.shape("DDD", "DBD", "DDD");
        recipe.setIngredient('D', Material.DIAMOND_ORE);
        recipe.setIngredient('B', Material.BARREL);
        Bukkit.addRecipe(recipe);
    }

    // --- СОХРАНЕНИЕ/ЗАГРУЗКА ---

    // Класс-обертка для данных
    private static class LudoData {
        double percentage = 0;
        int totalDeposited = 0;
    }

    private void loadData() {
        // Бочки
        if (getConfig().contains("barrels")) {
            barrelLocations.addAll(getConfig().getStringList("barrels"));
        }
        // Игроки
        if (getConfig().contains("players")) {
            ConfigurationSection sec = getConfig().getConfigurationSection("players");
            if (sec != null) {
                for (String uuidStr : sec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        LudoData data = new LudoData();
                        data.percentage = sec.getDouble(uuidStr + ".pct");
                        data.totalDeposited = sec.getInt(uuidStr + ".total");
                        addicts.put(uuid, data);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void saveAllData() {
        // Бочки
        getConfig().set("barrels", new ArrayList<>(barrelLocations));

        // Игроки
        getConfig().set("players", null); // Очистка старого
        for (Map.Entry<UUID, LudoData> entry : addicts.entrySet()) {
            String path = "players." + entry.getKey().toString();
            getConfig().set(path + ".pct", entry.getValue().percentage);
            getConfig().set(path + ".total", entry.getValue().totalDeposited);
        }
        saveConfig();
    }

    // Вспомогательные
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