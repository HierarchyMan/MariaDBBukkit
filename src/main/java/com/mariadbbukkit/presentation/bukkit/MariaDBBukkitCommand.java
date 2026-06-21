package com.mariadbbukkit.presentation.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class MariaDBBukkitCommand implements CommandExecutor {

    private final MariaDBBukkitPlugin plugin;

    public MariaDBBukkitCommand(MariaDBBukkitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status" -> sendStatus(sender);
            case "restart" -> {
                if (!sender.hasPermission("mariadbbukkit.admin")) {
                    sender.sendMessage("\u00A7cNo permission.");
                    return true;
                }
                sender.sendMessage("\u00A7eRestarting MariaDBBukkit...");
                try {
                    plugin.restart();
                    sender.sendMessage("\u00A7aMariaDBBukkit restarted. JDBC: " + plugin.getJdbcUrl());
                } catch (Exception e) {
                    sender.sendMessage("\u00A7cRestart failed: " + e.getMessage());
                    plugin.getLogger().severe("Restart failed: " + e);
                }
            }
            case "stop" -> {
                if (!sender.hasPermission("mariadbbukkit.admin")) {
                    sender.sendMessage("\u00A7cNo permission.");
                    return true;
                }
                sender.sendMessage("\u00A7eStopping MariaDBBukkit...");
                plugin.getServer().getPluginManager().disablePlugin(plugin);
            }
            default -> sender.sendMessage("\u00A7eUsage: /" + label + " [status|restart|stop]");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        if (plugin.isRunning()) {
            sender.sendMessage("\u00A7aMariaDBBukkit is running.");
            sender.sendMessage("\u00A77  Port: " + plugin.getPort());
            sender.sendMessage("\u00A77  DB:   " + plugin.getDatabaseName());
            sender.sendMessage("\u00A77  JDBC: " + plugin.getJdbcUrl());
        } else {
            sender.sendMessage("\u00A7cMariaDBBukkit is NOT running.");
        }
    }
}
