package com.bergerkiller.bukkit.lightcleaner;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.bergerkiller.bukkit.common.Common;
import com.bergerkiller.bukkit.common.PluginBase;
import com.bergerkiller.bukkit.common.config.FileConfiguration;
import com.bergerkiller.bukkit.common.permissions.NoPermissionException;
import com.bergerkiller.bukkit.lightcleaner.impl.Handler;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;

public class LightCleaner extends PluginBase {
    public static LightCleaner plugin;
    public static long minFreeMemory = 100 * 1024 * 1024;
    public static boolean autoCleanEnabled = false;
    public static int asyncLoadConcurrency = 50;
    public static boolean skipWorldEdge = true;
    public static final int WORLD_EDGE = 2;
    public static Set<String> unsavedWorldNames = new HashSet<String>();
    private boolean worldEditHandlerEnabled = false;
    private Handler worldEditHandler = null;

    public static boolean isWorldSaveEnabled(World world) {
        return !unsavedWorldNames.contains(world.getName());
    }

    @Override
    public int getMinimumLibVersion() {
        return Common.VERSION;
    }

    @Override
    public void permissions() {
        this.loadPermissions(Permission.class);
    }

    @Override
    public void enable() {
        plugin = this;

        register(new NLLListener());

        FileConfiguration config = new FileConfiguration(this);
        config.load();

        // Top header
        config.setHeader("This is the configuration of Light Cleaner, in here you can enable or disable features as you please");

        // Minimum free memory to perform fixes when loading chunks
        config.setHeader("minFreeMemory", "\nThe minimum amount of memory (in MB) allowed while processing chunk lighting");
        config.addHeader("minFreeMemory", "If the remaining free memory drops below this value, measures are taken to reduce it");
        config.addHeader("minFreeMemory", "Memory will be Garbage Collected and all worlds will be saved to free memory");
        config.addHeader("minFreeMemory", "The process will be stalled for so long free memory is below this value");

        int minFreeMemOption = config.get("minFreeMemory", 400);
        if (minFreeMemOption < 400) {
            log(Level.WARNING, "minFreeMemory is set to " + minFreeMemOption + "MB which is less than recommended (400MB)");
            log(Level.WARNING, "It is recommended to correct this in the config.yml of Light Cleaner, or risk out of memory errors");
        }
        minFreeMemory = (long) (1024 * 1024) * (long) minFreeMemOption;

        // Skips chunks at the edge of the world, that if cleaned, would cause more chunks to generate
        config.setHeader("skipWorldEdge", "\nWhether to skip processing chunks at the edge of the world");
        config.addHeader("skipWorldEdge", "Setting this to true prevents additional chunks being generated there");
        config.addHeader("skipWorldEdge", "This does mean the border chunks do not get cleaned");
        skipWorldEdge = config.get("skipWorldEdge", true);

        config.setHeader("autoCleanEnabled", "\nSets whether lighting is cleaned up for newly generated chunks");
        config.addHeader("autoCleanEnabled", "This will eliminate dark shadows during world generation");
        autoCleanEnabled = config.get("autoCleanEnabled", false);

        config.setHeader("autoCleanWorldEditEnabled", "\nSets whether lighting is cleaned up when players perform WorldEdit operations");
        config.addHeader("autoCleanWorldEditEnabled", "This is primarily useful for FastAsyncWorldEdit");
        worldEditHandlerEnabled = config.get("autoCleanWorldEditEnabled", false);

        config.setHeader("asyncLoadConcurrency", "\nHow many chunks are asynchronously loaded at the same time");
        config.addHeader("asyncLoadConcurrency", "Setting this value too high may overflow the internal queues. Too low and it will idle too much.");
        asyncLoadConcurrency = config.get("asyncLoadConcurrency", 50);

        config.setHeader("unsavedWorldNames", "\nA list of world names that have saving disabled");
        config.addHeader("unsavedWorldNames", "Light Cleaner will not save these worlds to free up memory,");
        config.addHeader("unsavedWorldNames", "and will not write persistent PendingLight.dat entries for these worlds");
        unsavedWorldNames.clear();
        unsavedWorldNames.addAll(config.getList("unsavedWorldNames", String.class, Arrays.asList("dummyUnsavedWorldName")));

        config.save();

        // Warn if no memory limit is set
        if (Runtime.getRuntime().maxMemory() == Long.MAX_VALUE && minFreeMemory > 0) {
            log(Level.WARNING, "No memory limitation is configured for Java. An out of memory condition might occur for large operations!");
            log(Level.WARNING, "To silence this warning, set minFreeMemory to 0 in config.yml");
        }

        LightingService.loadPendingBatches();
    }

    @Override
    public void disable() {        
        LightingService.abort();

        plugin = null;
    }

    @Override
    public void updateDependency(Plugin plugin, String pluginName, boolean enabled) {
        if (worldEditHandlerEnabled && providesWorldEdit(plugin)) {
            if (enabled && worldEditHandler == null) {
                try {
                    Class.forName("com.boydti.fawe.beta.IBatchProcessor");
                    worldEditHandler = new com.bergerkiller.bukkit.lightcleaner.impl.FastAsyncWorldEditHandler();
                } catch (ClassNotFoundException ex) {
                    worldEditHandler = new com.bergerkiller.bukkit.lightcleaner.impl.WorldEditHandler();
                }
                worldEditHandler.enable(this);
            } else if (!enabled && worldEditHandler != null) {
                worldEditHandler.disable(this);
                worldEditHandler = null;
                log(Level.INFO, "WorldEdit was disabled, support for automatic light cleaning turned off");
            }
        }
    }

    private static boolean providesWorldEdit(Plugin plugin) {
        if (plugin.getName().equalsIgnoreCase("worldedit")) {
            return true;
        }
        try {
            for (String provide : plugin.getDescription().getProvides()) {
                if ("worldedit".equalsIgnoreCase(provide)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean command(CommandSender sender, String command, String[] args) {
        try {
            String subCmd = (args.length == 0) ? "" : args[0];
            if (subCmd.equalsIgnoreCase("abort")) {
                // cleanlight abort
                Permission.ABORT.handle(sender);
                if (LightingService.isProcessing()) {
                    LightingService.clearTasks();
                    sender.sendMessage(ChatColor.GREEN + "All pending tasks cleared.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "No lighting was being processed; there was nothing to abort.");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("pause")) {
                // cleanlight pause
                Permission.PAUSE.handle(sender);
                LightingService.setPaused(true);
                sender.sendMessage(ChatColor.YELLOW + "Light cleaning " + ChatColor.RED + "paused");
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.RED.toString() + LightingService.getChunkFaults() + ChatColor.YELLOW + " chunks are pending");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("resume")) {
                // cleanlight resume
                Permission.PAUSE.handle(sender);
                LightingService.setPaused(false);
                sender.sendMessage(ChatColor.YELLOW + "Light cleaning " + ChatColor.GREEN + "resumed");
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.RED.toString() + LightingService.getChunkFaults() + ChatColor.YELLOW + " chunks will now be processed");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("status")) {
                // cleanlight status
                Permission.STATUS.handle(sender);
                if (LightingService.isProcessing()) {
                    if (LightingService.isPaused()) {
                        sender.sendMessage(ChatColor.YELLOW + "Light cleaning is currently paused, " + ChatColor.RED + LightingService.getChunkFaults() +
                                " " + ChatColor.YELLOW + "chunks remaining");
                        sender.sendMessage(ChatColor.YELLOW + "To start processing these chunks, use /cleanlight resume");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "Lighting is being cleaned, " + ChatColor.RED + LightingService.getChunkFaults() +
                                " " + ChatColor.YELLOW + "chunks remaining");

                        String message = ChatColor.YELLOW + "Current: " + ChatColor.GREEN + LightingService.getCurrentStatus();
                        java.util.OptionalLong startTime = LightingService.getCurrentStartTime();
                        if (startTime.isPresent()) {
                            long time = startTime.getAsLong();
                            if (time != 0) {
                                time = System.currentTimeMillis() - time;
                            }
                            if (time == 0) {
                                message += ChatColor.YELLOW + " (Starting...)";
                            } else if (time > (60*60*1000)) {
                                message += ChatColor.RED + " (>1h)";
                            } else if (time > 60000) {
                                message += ChatColor.RED + " (" + (time/60000) + "m)";
                            }
                        }
                        sender.sendMessage(message);
                    }
                } else {
                    sender.sendMessage(ChatColor.GREEN + "No lighting is being processed at this time.");
                }
                return true;
            }

            // All other commands are parsed into schedule arguments for the lighting scheduler
            LightingService.ScheduleArguments scheduleArgs = new LightingService.ScheduleArguments();
            if (scheduleArgs.handleCommandInput(sender, args)) {
                LightingService.schedule(scheduleArgs);
                LightingService.addRecipient(sender);
            }

        } catch (NoPermissionException ex) {
            if (sender instanceof Player) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this!");
            } else {
                sender.sendMessage("This command is only for players!");
            }
        }
        return true;
    }
}
