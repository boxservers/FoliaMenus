package com.extendedclip.deluxemenus.scheduler;

/**
 * Handle to a scheduled task that may originate from either the Bukkit/Paper global scheduler or
 * the Folia region/global region scheduler. Implementations are created by
 * {@link FoliaScheduler}.
 */
public interface DeluxeMenusTask {

    /**
     * Requests cancellation. Safe to call repeatedly. Implementations swallow "already cancelled"
     * errors.
     */
    void cancel();

    /**
     * @return true if the task is no longer scheduled to run.
     */
    boolean isCancelled();
}
