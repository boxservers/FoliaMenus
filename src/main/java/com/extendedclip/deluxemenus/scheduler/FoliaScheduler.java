package com.extendedclip.deluxemenus.scheduler;

import com.extendedclip.deluxemenus.DeluxeMenus;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Central scheduler facade. Picks between the Bukkit/Paper {@code BukkitScheduler} and Folia's
 * region-aware schedulers at runtime, so the same plugin jar runs on Spigot, Paper and Folia
 * without separate builds.
 *
 * <p><b>Threading rules (Folia):</b>
 * <ul>
 *   <li>Anything that touches a {@link Player} or any entity must run on that entity's owning
 *       region thread. Use {@link #runForPlayer(Player, Runnable)} etc.</li>
 *   <li>Anything that touches world state, blocks or inventories must run on the owning region
 *       thread.</li>
 *   <li>Plugin-global, thread-safe work (HTTP fetches, sweeping in-memory maps, listener
 *       registration) may run on the global scheduler or async scheduler. Use
 *       {@link #runGlobal(Runnable)}, {@link #runGlobalAsync(Runnable)} and timers.</li>
 * </ul>
 *
 * <p>On Paper/Spigot all of these methods funnel through the same global Bukkit scheduler, so the
 * call sites are identical.
 */
public final class FoliaScheduler {

    private final Plugin plugin;
    private final boolean folia;

    public FoliaScheduler(final @NotNull Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    public boolean isFolia() {
        return folia;
    }

    // ---- Detection ----------------------------------------------------------

    private static boolean detectFolia() {
        try {
            // Only Folia exposes a GlobalRegionScheduler on the Bukkit server.
            return Bukkit.class.getMethod("getGlobalRegionScheduler") != null
                    && Bukkit.getGlobalRegionScheduler() != null;
        } catch (final NoSuchMethodException | AbstractMethodError ignored) {
            return false;
        }
    }

    // ---- Global scheduling --------------------------------------------------

    /**
     * Runs {@code runnable} on the next global tick. Safe to call from any thread.
     */
    public DeluxeMenusTask runGlobal(final @NotNull Runnable runnable) {
        if (folia) {
            final ScheduledTask task = Bukkit.getGlobalRegionScheduler().run(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run());
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    /**
     * Runs {@code runnable} on the global region scheduler after {@code ticks} have elapsed.
     */
    public DeluxeMenusTask runGlobalLater(final @NotNull Runnable runnable, final long ticks) {
        if (folia) {
            final ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run(), ticks);
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, ticks));
    }

    /**
     * Runs {@code runnable} asynchronously on Folia's async scheduler, or on a free async thread
     * pool on Paper/Spigot.
     */
    public DeluxeMenusTask runGlobalAsync(final @NotNull Runnable runnable) {
        if (folia) {
            final ScheduledTask task = Bukkit.getAsyncScheduler().runNow(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run());
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    /**
     * Repeating global task. The first invocation fires after {@code delayTicks}.
     */
    public DeluxeMenusTask runGlobalTimerAsync(final @NotNull Runnable runnable, final long delayTicks, final long periodTicks) {
        if (folia) {
            final AsyncScheduler async = Bukkit.getAsyncScheduler();
            final long delayMs = Math.max(1L, delayTicks) * 50L;
            final long periodMs = Math.max(1L, periodTicks) * 50L;
            final ScheduledTask task = async.runAtFixedRate(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks));
    }

    // ---- Region (player) scheduling ----------------------------------------

    /**
     * Runs {@code runnable} on the region thread that owns {@code player}. If the player has gone
     * offline the task is dropped.
     */
    public DeluxeMenusTask runForPlayer(final @NotNull Player player, final @NotNull Runnable runnable) {
        if (folia) {
            if (!player.isOnline()) {
                return new NoopTask();
            }
            final ScheduledTask task = player.getScheduler().run(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run(), () -> {
            });
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    /**
     * Runs {@code runnable} on the region thread that owns {@code player} after {@code ticks}.
     */
    public DeluxeMenusTask runForPlayerLater(final @NotNull Player player, final @NotNull Runnable runnable, final long ticks) {
        if (folia) {
            if (!player.isOnline()) {
                return new NoopTask();
            }
            final ScheduledTask task = player.getScheduler().runDelayed(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run(), () -> {
            }, ticks);
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, ticks));
    }

    /**
     * Runs {@code runnable} on the region thread that owns {@code entity} after {@code ticks}.
     */
    public DeluxeMenusTask runForEntityLater(final @NotNull Entity entity, final @NotNull Runnable runnable, final long ticks) {
        if (folia) {
            if (entity instanceof Player) {
                final Player player = (Player) entity;
                if (!player.isOnline()) {
                    return new NoopTask();
                }
            }
            final ScheduledTask task = entity.getScheduler().runDelayed(plugin, (Consumer<ScheduledTask>) scheduledTask -> runnable.run(), null, ticks);
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, ticks));
    }

    /**
     * Repeating region task. The first invocation fires after {@code delayTicks} on the region
     * thread owning {@code player}.
     */
    public DeluxeMenusTask runForPlayerTimer(final @NotNull Player player, final @NotNull Runnable runnable, final long delayTicks, final long periodTicks) {
        if (folia) {
            if (!player.isOnline()) {
                return new NoopTask();
            }
            final RegionScheduler region = Bukkit.getRegionScheduler();
            final ScheduledTask task = region.runAtFixedRate(plugin, player.getLocation(), (Consumer<ScheduledTask>) scheduledTask -> runnable.run(), delayTicks, periodTicks);
            return new FoliaTask(task);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
    }

    /**
     * Repeating region task that may run async (Folia) or async (Paper). The task body itself is
     * expected to bounce back to the region thread when it touches player/world state, just as
     * the existing async-timer code already does.
     */
    public DeluxeMenusTask runForPlayerTimerAsync(final @NotNull Player player, final @NotNull Runnable runnable, final long delayTicks, final long periodTicks) {
        if (folia) {
            // No async-by-entity scheduler; the per-entity scheduler's repeating overload is
            // already region-locked, so async callers must wrap via runForPlayer themselves.
            return runForPlayerTimer(player, runnable, delayTicks, periodTicks);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks));
    }

    /**
     * Cancels every task owned by this plugin. Mirrors {@code BukkitScheduler#cancelTasks} which
     * is unavailable on Folia.
     */
    public void cancelAll() {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    // ---- Helpers for call sites that already hold a Plugin reference ------

    // ---- Wrappers -----------------------------------------------------------

    private static final class BukkitTaskHandle implements DeluxeMenusTask {
        private final org.bukkit.scheduler.BukkitTask delegate;

        BukkitTaskHandle(final org.bukkit.scheduler.BukkitTask delegate) {
            this.delegate = delegate;
        }

        @Override
        public void cancel() {
            try {
                delegate.cancel();
            } catch (final Throwable ignored) {
            }
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }

    private static final class FoliaTask implements DeluxeMenusTask {
        private final ScheduledTask delegate;

        FoliaTask(final ScheduledTask delegate) {
            this.delegate = delegate;
        }

        @Override
        public void cancel() {
            try {
                delegate.cancel();
            } catch (final Throwable ignored) {
            }
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }

    private static final class NoopTask implements DeluxeMenusTask {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    }
}
