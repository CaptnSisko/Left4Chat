package me.sisko.left4chat.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;

public class AnnounceCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String str, String[] args) {
        if (!(sender instanceof Player)) {
            String msg = "";
            for (String arg : args) {
                msg = String.valueOf(msg) + arg + " ";
            }
            Jedis j = new Jedis();
            j.publish("minecraft.chat.global.in", msg);
            j.close();
        } else {
            sender.sendMessage(ChatColor.RED + "Insufficient Permission.");
        }
        return true;
    }
}
