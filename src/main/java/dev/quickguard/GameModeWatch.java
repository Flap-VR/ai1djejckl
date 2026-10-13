package dev.quickguard;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bans non-op players who switch to creative/spectator, unless a staff member just did it for them. */
public final class GameModeWatch implements Listener {

    private static final Pattern GM_COMMAND = Pattern.compile(
            "^/?(?:[a-z]+:)?(?:e?gamemode|e?gm|e?gmc|e?gms|e?gma|e?gmsp|creative|survival|adventure|spectator|defaultgamemode)(?:\\s.*)?$",
            Pattern.CASE_INSENSITIVE);

    private final QuickGuard plugin;
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private long lastStaffGamemodeCommand = 0L;

    public GameModeWatch(QuickGuard plugin) {
        this.plugin = plugin;
    }

    private boolean staffJustChangedGamemode(long windowMs) {
        return System.currentTimeMillis() - lastStaffGamemodeCommand < windowMs;
    }

    // A gamemode command from an op, someone with bypass, or the console = an allowed change.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (plugin.isStaff(e.getPlayer()) && GM_COMMAND.matcher(e.getMessage()).matches()) {
            lastStaffGamemodeCommand = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent e) {
        if (GM_COMMAND.matcher(e.getCommand()).matches()) {
            lastStaffGamemodeCommand = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(PlayerGameModeChangeEvent e) {
        if (!plugin.getConfig().getBoolean("checks.gamemode.enabled", true)) return;
        Player p = e.getPlayer();
        if (plugin.isStaff(p)) return;

        GameMode to = e.getNewGameMode();
        if (!blockedModes().contains(to.name())) return;
        if (staffJustChangedGamemode(3000L)) return;

        // The game applies a saved gamemode while logging in; never punish for that.
        Long joined = joinedAt.get(p.getUniqueId());
        if (joined != null && System.currentTimeMillis() - joined < 10_000L) return;

        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.punish(p, "gamemode", "tried to switch to " + to.name());
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());

        if (!plugin.getConfig().getBoolean("fix-gamemode-on-join", true)) return;
        // Not a ban: someone left in creative earlier (or similar) gets put back to survival.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || plugin.isStaff(p)) return;
            if (staffJustChangedGamemode(10_000L)) return;
            GameMode now = p.getGameMode();
            if (blockedModes().contains(now.name())) {
                p.setGameMode(GameMode.SURVIVAL);
                plugin.alert(p, "gamemode", "joined in " + now.name() + ", set back to SURVIVAL", "RESET");
                plugin.log(p.getName() + " | gamemode | RESET | joined in " + now.name());
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        joinedAt.remove(e.getPlayer().getUniqueId());
    }

    private List<String> blockedModes() {
        return plugin.getConfig().getStringList("checks.gamemode.modes").stream()
                .map(String::toUpperCase).toList();
    }
}
