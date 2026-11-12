package com.dobeshadow.minestatus.command;

import com.dobeshadow.minestatus.MineStatusPlugin;
import com.dobeshadow.minestatus.push.StatusPusher;
import com.dobeshadow.minestatus.skin.HeadCache;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * /minestatus 命令实现。
 * <p>
 * 子命令：push（立即推送）、reload（重载配置）、status（查看上次推送结果）。
 */
public class MineStatusCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("push", "reload", "status", "heads");

    private final MineStatusPlugin plugin;

    public MineStatusCommand(MineStatusPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("minestatus.admin")) {
            sender.sendMessage("§c你没有权限使用该命令。");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "push" -> {
                sender.sendMessage("§e正在推送在线玩家状态...");
                plugin.pushNow(result -> {
                    if (result.success()) {
                        sender.sendMessage("§a" + result.message());
                    } else {
                        sender.sendMessage("§c" + result.message());
                    }
                });
            }
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage("§a配置已重载，定时任务已重置。");
                if (!plugin.getConfigManager().isEndpointConfigured()) {
                    sender.sendMessage("§e提醒：endpoint 或 token 尚未正确配置，自动推送将被跳过。");
                }
            }
            case "heads" -> {
                HeadCache hc = plugin.getHeadCache();
                sender.sendMessage("§6===== 玩家头像历史缓存 =====");
                sender.sendMessage("§7已缓存玩家: §f" + hc.size() + " / "
                        + plugin.getConfigManager().getHeadCacheMaxPlayers());
                sender.sendMessage("§7待推送到网页: §f" + (hc.isDirty() ? "是（下轮推送带上）" : "否"));
                sender.sendMessage("§7持久化文件: §fplugins/MineStatus/heads.json");
                sender.sendMessage("§7新玩家上线后会自动记录，离线玩家在榜单即可显示真实皮肤。");
            }
            case "status" -> {
                StatusPusher.PushResult last = plugin.getStatusPusher().getLastResult();
                sender.sendMessage("§6===== MineStatus 状态 =====");
                sender.sendMessage("§7自动推送: §f" + (plugin.getConfigManager().isEnabled() ? "开启" : "关闭"));
                sender.sendMessage("§7推送间隔: §f" + plugin.getConfigManager().getIntervalSeconds() + " 秒");
                sender.sendMessage("§7在线人数: §f" + plugin.getServer().getOnlinePlayers().size()
                        + " / " + plugin.getServer().getMaxPlayers());
                sender.sendMessage("§7上次推送: §f" + last.message());
                if (last.timestamp() > 0) {
                    String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(last.timestamp()));
                    sender.sendMessage("§7推送时间: §f" + time);
                }
            }
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("§6===== MineStatus =====");
        sender.sendMessage("§e/" + label + " push §7- 立即推送一次");
        sender.sendMessage("§e/" + label + " reload §7- 重载配置并重置定时器");
        sender.sendMessage("§e/" + label + " status §7- 查看当前状态");
        sender.sendMessage("§e/" + label + " heads §7- 查看玩家头像历史缓存");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("minestatus.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }
        return List.of();
    }
}
