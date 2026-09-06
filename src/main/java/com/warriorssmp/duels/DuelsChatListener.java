package com.warriorssmp.duels;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class DuelsChatListener implements Listener {

    private final DuelsPlugin plugin;
    private final AdminGUI adminGUI;
    private final BettingManager bettingManager;

    public DuelsChatListener(DuelsPlugin plugin, AdminGUI adminGUI, BettingManager bettingManager) {
        this.plugin = plugin;
        this.adminGUI = adminGUI;
        this.bettingManager = bettingManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        BettingManager.PendingBet awaitingBet = bettingManager.getAwaitingAmount(player.getUniqueId());
        if (awaitingBet != null) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Bukkit.getScheduler().runTask(plugin, () -> bettingManager.consumeAmountInput(player, awaitingBet, text));
            return;
        }

        AdminGUI.AwaitingInput awaiting = adminGUI.getAwaitingInput(player.getUniqueId());
        if (awaiting == null) return;

        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> adminGUI.consumeChatInput(player, awaiting, text));
    }
}
