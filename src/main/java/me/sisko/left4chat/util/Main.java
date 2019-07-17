package me.sisko.left4chat.util;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.loyloy.nicky.Nick;
import io.loyloy.nicky.Nicky;
import me.sisko.left4chat.commands.AfkCommand;
import me.sisko.left4chat.commands.AnnounceCommand;
import me.sisko.left4chat.commands.DiscordCommand;
import me.sisko.left4chat.commands.GGiveCosmeticCommand;
import me.sisko.left4chat.commands.GameCommand;
import me.sisko.left4chat.commands.LockdownCommand;
import me.sisko.left4chat.commands.MessageCommand;
import me.sisko.left4chat.commands.MessageTabComplete;
import me.sisko.left4chat.commands.ReloadCommand;
import me.sisko.left4chat.commands.ReplyCommand;
import me.sisko.left4chat.commands.VerifyCommand;
import me.sisko.left4chat.sql.AsyncFixCoins;
import me.sisko.left4chat.sql.AsyncKeepAlive;
import me.sisko.left4chat.sql.AsyncUserUpdate;
import me.sisko.left4chat.sql.SQLManager;
import me.sisko.left4chat.util.CheckAFKTask;
import me.sisko.left4chat.util.ConfigManager;
import me.sisko.left4chat.util.InventoryGUI;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public class Main
extends JavaPlugin
implements Listener {
    public static Main plugin;
    private static Connection connection;
    private static Permission perms;
    private static Chat chat;
    private static ArrayList<Player> afkPlayers;
    private static HashMap<Player, Long> moveTimes;
    private static HashMap<Player, Integer> warnings;

    static {
        perms = null;
        chat = null;
    }

    public void onEnable() {
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        this.getServer().getMessenger().registerOutgoingPluginChannel((Plugin)this, "BungeeCord");
        plugin = this;
        this.getCommand("discord").setExecutor(new DiscordCommand());
        this.getCommand("announce").setExecutor(new AnnounceCommand());
        this.getCommand("game").setExecutor(new GameCommand());
        this.getCommand("msg").setExecutor(new MessageCommand());
        this.getCommand("msg").setTabCompleter(new MessageTabComplete());
        this.getCommand("reply").setExecutor(new ReplyCommand());
        this.getCommand("afk").setExecutor(new AfkCommand());
        this.getCommand("ggivecosmetic").setExecutor(new GGiveCosmeticCommand());
        this.getCommand("verify").setExecutor(new VerifyCommand());
        this.getCommand("chatlock").setExecutor(new LockdownCommand());
        this.getCommand("chatreload").setExecutor(new ReloadCommand());
        afkPlayers = new ArrayList<Player>();
        moveTimes = new HashMap<Player, Long>();
        warnings = new HashMap<Player, Integer>();
        RegisteredServiceProvider<Permission> rspPerm = this.getServer().getServicesManager().getRegistration(Permission.class);
        perms = (Permission)rspPerm.getProvider();
        RegisteredServiceProvider<Chat> rspChat = this.getServer().getServicesManager().getRegistration(Chat.class);
        chat = (Chat)rspChat.getProvider();
        InventoryGUI.setup();
        connection = SQLManager.connect();
        new AsyncKeepAlive(connection).runTaskTimerAsynchronously((Plugin)this, 0L, 72000L);
        new CheckAFKTask().runTaskTimer((Plugin)this, 0L, 200L);
        this.subscribe();
        ConfigManager.load();
    }

    public static String getCodes() {
        Jedis j = new Jedis();
        String codes = j.get("discord.synccodes");
        j.close();
        return codes;
    }

    public static String getGroup(String w, OfflinePlayer op) {
        return perms.getPrimaryGroup(w, op);
    }

    public static Connection getSQL() {
        return connection;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.setJoinMessage(null);
        new AsyncFixCoins(getSQL(), e.getPlayer()).runTaskLaterAsynchronously(this, 20);
        try {
            new AsyncUserUpdate().setup(connection, e.getPlayer()).runTaskAsynchronously((Plugin)this);
        }
        catch (Exception e2) {
            new AsyncUserUpdate().setup(connection, e.getPlayer()).runTaskAsynchronously((Plugin)this);
        }
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent e) {
        if (warnings.get(e.getPlayer()) != null) {
            warnings.remove(e.getPlayer());
        }
        this.setAFK(e.getPlayer(), false, false);
        moveTimes.remove(e.getPlayer());
        e.setQuitMessage(null);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        e.getFrom().distance(e.getTo());
        moveTimes.put(e.getPlayer(), System.currentTimeMillis());
        this.setAFK(e.getPlayer(), false, true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        moveTimes.put(e.getPlayer(), System.currentTimeMillis());
        this.setAFK(e.getPlayer(), false, true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        moveTimes.put(e.getPlayer(), System.currentTimeMillis());
        this.setAFK(e.getPlayer(), false, true);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Jedis j = new Jedis();
        if (j.get("minecraft.lockdown").equals("true") && !perms.has(e.getPlayer(), "left4chat.verified")) {
            if (VerifyCommand.playerVerifying(e.getPlayer())) {
                j.publish("minecraft.console.hub.in", "kick " + e.getPlayer().getName() + " Incorrect CAPTCHA solution");
                j.close();
                return;
            }
            if (warnings.get(e.getPlayer()) == null) {
                e.getPlayer().sendMessage(ChatColor.RED + "You must verify your account with " + ChatColor.GOLD + "/verify" + ChatColor.RED + " before chatting or you will be " + ChatColor.BOLD + "permbanned" + ChatColor.RED + " (Warning 1/5)");
                warnings.put(e.getPlayer(), 2);
                e.setCancelled(true);
                j.close();
                return;
            }
            if (warnings.get(e.getPlayer()) <= 5) {
                e.getPlayer().sendMessage(ChatColor.RED + "You must verify your account with " + ChatColor.GOLD + "/verify" + ChatColor.RED + " before chatting or you will be " + ChatColor.BOLD + "permbanned" + ChatColor.RED + " (Warning " + warnings.get(e.getPlayer()) + "/5)");
                warnings.put(e.getPlayer(), warnings.get(e.getPlayer()) + 1);
                e.setCancelled(true);
                j.close();
                return;
            }
            warnings.remove(e.getPlayer());
            j.publish("minecraft.console.hub.in", "ban " + e.getPlayer().getName() + " Spambot (appealable)");
            e.setCancelled(true);
            j.close();
            return;
        }
        this.setAFK(e.getPlayer(), false, true);
        moveTimes.put(e.getPlayer(), System.currentTimeMillis());
        String group = perms.getPrimaryGroup(e.getPlayer());
        String name = new Nick(e.getPlayer()).get();
        if (name == null) {
            name = e.getPlayer().getName();
        }
        name = ChatColor.translateAlternateColorCodes('&', name);
        j.publish("minecraft.chat.global.out", "**" + e.getPlayer().getUniqueId() + "**[" + group + "] " + ChatColor.stripColor((String)(String.valueOf(name) + "** " + e.getMessage())).replaceAll("&.", ""));
        String message = e.getMessage();
        if (!perms.has(e.getPlayer(), "left4chat.format")) {
            if (perms.has(e.getPlayer(), "left4chat.color")) {
                String[] formats = {"&l", "&k", "&m", "&n", "&o"};
                for (String format : formats) {
                    while (message.contains(format)) {
                        message = message.replace(format, "");
                    }
                }
            } else {
                message = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
        j.publish("minecraft.chat.global.in", String.valueOf(chat.getPlayerPrefix(e.getPlayer())) + name + ChatColor.RESET + " " + message);
        e.setCancelled(true);
        j.close();
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        Player p = (Player)e.getWhoClicked();
        moveTimes.put(p, System.currentTimeMillis());
        this.setAFK(p, false, true);
        if (e.getClickedInventory() != null && e.getView().getTitle().equals(InventoryGUI.getName())) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked.getType() == Material.NETHER_STAR) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF("hub");
                p.sendPluginMessage((Plugin)this, "BungeeCord", out.toByteArray());
            } else if (clicked.getType() == Material.GRASS_BLOCK) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF("survival");
                p.sendPluginMessage((Plugin)this, "BungeeCord", out.toByteArray());
            } else if (clicked.getType() == Material.DIAMOND) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF("creative");
                p.sendPluginMessage((Plugin)this, "BungeeCord", out.toByteArray());
            } else if (clicked.getType() == Material.ZOMBIE_HEAD) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF("zombies");
                p.sendPluginMessage((Plugin)this, "BungeeCord", out.toByteArray());
            }
            p.closeInventory();
            p.updateInventory();
            e.setCancelled(true);
        } else if (VerifyCommand.playerVerifying(p)) {
            ItemStack clicked = e.getCurrentItem();
            if (VerifyCommand.getSolution(p) == VerifyCommand.getType(clicked.getType())) {
                p.sendMessage(ChatColor.GREEN + "Account Verified! You may not chat freely.");
                p.closeInventory();
                p.updateInventory();
                Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("pp user " + p.getUniqueId() + " add left4chat.verified"));
            } else {
                p.sendMessage(ChatColor.RED + "Incorrect CAPTCHA response!");
                p.closeInventory();
                p.updateInventory();
                Jedis j = new Jedis();
                j.publish("minecraft.console.hub.in", "kick " + e.getWhoClicked().getName() + " Incorrect CAPTCHA solution");
                j.close();
            }
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Player p = (Player)e.getPlayer();
        if (VerifyCommand.playerVerifying(p)) {
            p.sendMessage(ChatColor.RED + "Kicked for incorrect CAPTCHA response!");
            Jedis j = new Jedis();
            j.publish("minecraft.console.hub.in", "kick " + e.getPlayer().getName() + " Incorrect CAPTCHA solution");
            j.close();
        }
    }

    private JedisPubSub subscribe() {
        final JedisPubSub jedisPubSub = new JedisPubSub(){

            @Override
            public void onMessage(String channel, String message) {
                if (channel.equals("minecraft.chat.global.in")) {
                    Main.this.getServer().broadcastMessage(ChatColor.translateAlternateColorCodes((char)'&', (String)message));
                } else if (channel.equals("minecraft.chat.messages")) {
                    Main.this.getLogger().info("Message: " + message);
                    String sender = message.split(",")[0];
                    String reciever = message.split(",")[1];
                    String contents = "";
                    for (int i = 2; i < message.split(",").length; ++i) {
                        contents = String.valueOf(contents) + message.split(",")[i] + ",";
                    }
                    contents = contents.substring(0, contents.length() - 1);
                    Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                    for (Player p : players) {
                        if (!p.getName().equalsIgnoreCase(reciever)) continue;
                        Jedis jedis = new Jedis();
                        for (String player : jedis.get("minecraft.players").split(",")) {
                            if (!player.split(" ")[0].equalsIgnoreCase(sender)) continue;
                            String nick = Nicky.getNickDatabase().downloadNick(player.split(" ")[1]);
                            if (nick == null) {
                                nick = sender;
                            }
                            p.sendMessage(ChatColor.translateAlternateColorCodes((char)'&', (String)("&c[&6" + nick + " &c-> &6You&c]&r " + contents)));
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 5.0f, 1.5f);
                        }
                        String tempJson = jedis.get("minecraft.chat.replies");
                        String[] parts = tempJson.replace(" ", "").split(",");
                        HashMap<String, String> replies = new HashMap<String, String>();
                        try {
                            for (int i = 0; i < parts.length; ++i) {
                                parts[i] = parts[i].replace("\"", "");
                                parts[i] = parts[i].replace("{", "");
                                parts[i] = parts[i].replace("}", "");
                                String[] subparts = parts[i].split(":");
                                replies.put(subparts[0], subparts[1]);
                            }
                        }
                        catch (ArrayIndexOutOfBoundsException e) {
                            p.sendMessage(ChatColor.RED + "Invalid JSON in minecraft.chat.replies");
                        }
                        replies.put(reciever, sender);
                        jedis.set("minecraft.chat.replies", new JSONObject(replies).toJSONString());
                        jedis.close();
                    }
                }
            }
        };
        new Thread(new Runnable(){

            @Override
            public void run() {
                try {
                    Jedis jedis = new Jedis();
                    jedis.subscribe(jedisPubSub, "minecraft.chat.global.in", "minecraft.chat.messages");
                    Main.this.getLogger().warning("Subscriber closed!");
                    jedis.quit();
                    jedis.close();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "subscriberThread").start();
        return jedisPubSub;
    }

    public Permission getPerms() {
        return perms;
    }

    public void toggleAfk(Player p) {
        String name = p.getName();
        Jedis jedis = new Jedis();
        if (afkPlayers.contains(p)) {
            afkPlayers.remove(p);
            Set<String> afkList = new HashSet<String>(Arrays.asList(jedis.get("minecraft.afkplayers").split(",")));
            afkList.addAll(afkPlayers.stream().map(Player::getName).distinct().collect(Collectors.toList()));
            if(afkList.contains(p.getName())) afkList.remove(p.getName());
            jedis.set("minecraft.afkplayers", String.join(",", afkList));
            jedis.publish("minecraft.chat.global.in", "&7 * " + name + " is no longer afk");
            jedis.publish("minecraft.chat.global.out", ":exclamation: " + name + " is no longer afk.");
            perms.playerRemove(p, "harbor.bypass");
        } else {
            afkPlayers.add(p);
            Set<String> afkList = new HashSet<String>(Arrays.asList(jedis.get("minecraft.afkplayers").split(",")));
            afkList.addAll(afkPlayers.stream().map(Player::getName).distinct().collect(Collectors.toList()));
            jedis.set("minecraft.afkplayers", String.join(",", afkList));
            jedis.publish("minecraft.chat.global.in", "&7 * " + name + " is now afk");
            jedis.publish("minecraft.chat.global.out", ":exclamation: " + name + " is now afk.");
            perms.playerAdd(p, "harbor.bypass");
        }
        jedis.set("minecraft.afkplayers", String.join((CharSequence)",", afkPlayers.stream().map(Player::getName).collect(Collectors.toList())));
        jedis.close();
    }

    public void setAFK(Player p, boolean afk, boolean verbose) {
        String name = p.getName();
        Jedis jedis = new Jedis();
        if (afk && !afkPlayers.contains(p)) {
            afkPlayers.add(p);
            Set<String> afkList = new HashSet<String>(Arrays.asList(jedis.get("minecraft.afkplayers").split(",")));
            afkList.addAll(afkPlayers.stream().map(Player::getName).distinct().collect(Collectors.toList()));
            jedis.set("minecraft.afkplayers", String.join(",", afkList));
            if (verbose) {
                jedis.publish("minecraft.chat.global.in", "&7 * " + name + " is now afk.");
                jedis.publish("minecraft.chat.global.out", ":exclamation: " + name + " is now afk.");
            }
            perms.playerAdd(p, "harbor.bypass");
        } else if (!afk && afkPlayers.contains(p)) {
            afkPlayers.remove(p);
            Set<String> afkList = new HashSet<String>(Arrays.asList(jedis.get("minecraft.afkplayers").split(",")));
            afkList.addAll(afkPlayers.stream().map(Player::getName).distinct().collect(Collectors.toList()));
            if(afkList.contains(p.getName())) afkList.remove(p.getName());
            jedis.set("minecraft.afkplayers", String.join(",", afkList));
            if (verbose) {
                jedis.publish("minecraft.chat.global.in", "&7 * " + name + " is no longer afk.");
                jedis.publish("minecraft.chat.global.out", ":exclamation: " + name + " is no longer afk.");
            }
            perms.playerRemove(p, "harbor.bypass");
        }
        jedis.close();
    }

    public HashMap<Player, Long> getMoveTimes() {
        return moveTimes;
    }

    public static Main getPlugin() {
        return plugin;
    }

    public static void ReconnectSQL() {
        connection = SQLManager.connect();
    }

}
