package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lets spectators bet on the outcome of an active duel or war match, in
 * either Vault money or Honor points (bettor's choice). Payout is
 * parimutuel per currency: everyone who bet on the winning side gets
 * their stake back plus a share of everyone who bet on a losing side's
 * stakes, proportional to how much they personally staked - this is the
 * standard, self-balancing way to run in-game betting, since it needs no
 * fixed odds and can never let the server "lose" money it didn't already
 * collect from losing bettors.
 */
public class BettingManager {

    private final DuelsPlugin plugin;
    private final DuelManager duelManager;
    private final WarManager warManager;
    private final GauntletManager gauntletManager;
    private final PlayerDataManager playerDataManager;
    private final VaultEconomy vaultEconomy;

    private final Map<MatchRef, List<Bet>> betsByMatch = new HashMap<>();
    private final Map<UUID, PendingBet> awaitingAmount = new HashMap<>();

    public BettingManager(DuelsPlugin plugin, DuelManager duelManager, WarManager warManager, GauntletManager gauntletManager,
                           PlayerDataManager playerDataManager, VaultEconomy vaultEconomy) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.warManager = warManager;
        this.gauntletManager = gauntletManager;
        this.playerDataManager = playerDataManager;
        this.vaultEconomy = vaultEconomy;
    }

    public VaultEconomy getVaultEconomy() {
        return vaultEconomy;
    }

    /** A bet choice (match, side, currency) that's fully picked except
     *  for the amount, which is entered via a chat message next. */
    public record PendingBet(MatchKind kind, UUID matchId, int sideIndex, BetCurrency currency) {}

    public PendingBet getAwaitingAmount(UUID bettorId) {
        return awaitingAmount.get(bettorId);
    }

    public void beginAmountEntry(Player bettor, MatchKind kind, UUID matchId, int sideIndex, BetCurrency currency) {
        awaitingAmount.put(bettor.getUniqueId(), new PendingBet(kind, matchId, sideIndex, currency));
        bettor.closeInventory();
        bettor.sendMessage(ChatColor.YELLOW + "Type the amount to bet on " + sideName(kind, matchId, sideIndex)
                + " (" + currency.getDisplayName() + "), or type 'cancel':");
    }

    /** Called by the chat listener once the bettor types their amount. */
    public void consumeAmountInput(Player bettor, PendingBet pending, String text) {
        awaitingAmount.remove(bettor.getUniqueId());
        if (text.equalsIgnoreCase("cancel")) {
            bettor.sendMessage(err("Bet cancelled."));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            bettor.sendMessage(err("That's not a valid number - bet cancelled, try again from the menu."));
            return;
        }
        placeBet(bettor, pending.kind(), pending.matchId(), pending.sideIndex(), amount, pending.currency());
    }

    public boolean isMatchActive(MatchKind kind, UUID matchId) {
        if (kind == MatchKind.DUEL) return duelManager.getActiveMatchById(matchId) != null;
        if (kind == MatchKind.GAUNTLET) return gauntletManager.getRunById(matchId) != null;
        return warManager.getActiveMatchById(matchId) != null;
    }

    public boolean isParticipant(MatchKind kind, UUID matchId, UUID playerId) {
        if (kind == MatchKind.DUEL) {
            DuelMatch match = duelManager.getActiveMatchById(matchId);
            return match != null && match.involves(playerId);
        }
        if (kind == MatchKind.GAUNTLET) {
            GauntletRun run = gauntletManager.getRunById(matchId);
            return run != null && run.getPlayers().contains(playerId);
        }
        WarMatch match = warManager.getActiveMatchById(matchId);
        return match != null && match.allParticipants().contains(playerId);
    }

    private boolean hasBet(MatchKind kind, UUID matchId, UUID bettorId) {
        List<Bet> bets = betsByMatch.get(new MatchRef(kind, matchId));
        if (bets == null) return false;
        for (Bet bet : bets) {
            if (bet.bettorId().equals(bettorId)) return true;
        }
        return false;
    }

    public void placeBet(Player bettor, MatchKind kind, UUID matchId, int sideIndex, double amount, BetCurrency currency) {
        if (!isMatchActive(kind, matchId)) {
            bettor.sendMessage(err("That match has already ended."));
            return;
        }
        if (isParticipant(kind, matchId, bettor.getUniqueId())) {
            bettor.sendMessage(err("You can't bet on a match you're fighting in."));
            return;
        }
        if (hasBet(kind, matchId, bettor.getUniqueId())) {
            bettor.sendMessage(err("You've already placed a bet on this match."));
            return;
        }
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            bettor.sendMessage(err("Bet amount must be a positive number."));
            return;
        }

        if (currency == BetCurrency.VAULT) {
            if (!vaultEconomy.isAvailable()) {
                bettor.sendMessage(err("Vault isn't available on this server - try Honor points instead."));
                return;
            }
            if (!vaultEconomy.has(bettor, amount)) {
                bettor.sendMessage(err("You don't have that much money."));
                return;
            }
            if (!vaultEconomy.withdraw(bettor, amount)) {
                bettor.sendMessage(err("Something went wrong taking your money - bet not placed."));
                return;
            }
        } else {
            int intAmount = (int) Math.round(amount);
            if (intAmount <= 0) {
                bettor.sendMessage(err("Bet amount must be at least 1 honor."));
                return;
            }
            PlayerDuelData data = playerDataManager.get(bettor.getUniqueId());
            if (data.getHonor() < intAmount) {
                bettor.sendMessage(err("You don't have that much honor."));
                return;
            }
            data.adjustHonorForBet(-intAmount);
            playerDataManager.save();
            amount = intAmount;
        }

        MatchRef ref = new MatchRef(kind, matchId);
        betsByMatch.computeIfAbsent(ref, k -> new ArrayList<>())
                .add(new Bet(bettor.getUniqueId(), sideIndex, amount, currency));
        bettor.sendMessage(ok("Bet placed: " + formatAmount(amount, currency) + " on "
                + sideName(kind, matchId, sideIndex) + "."));
    }

    /** Parimutuel payout, resolved separately per currency so Vault money
     *  and Honor points never mix pools. Winners split whatever the
     *  losing side staked, proportional to their own stake, plus get
     *  their own stake back. If nobody bet against the winners, they
     *  simply get their stake back with no profit - and since a winner's
     *  own stake is always part of the winning pool, that pool can never
     *  be zero when there's a winner to pay, so there's no division by
     *  zero to guard against. */
    public void resolveBets(MatchKind kind, UUID matchId, int winningSideIndex) {
        List<Bet> bets = betsByMatch.remove(new MatchRef(kind, matchId));
        if (bets == null || bets.isEmpty()) return;

        for (BetCurrency currency : BetCurrency.values()) {
            double winPool = 0;
            double losePool = 0;
            List<Bet> winners = new ArrayList<>();
            for (Bet bet : bets) {
                if (bet.currency() != currency) continue;
                if (bet.sideIndex() == winningSideIndex) {
                    winPool += bet.amount();
                    winners.add(bet);
                } else {
                    losePool += bet.amount();
                }
            }
            for (Bet bet : winners) {
                double payout = bet.amount() + losePool * (bet.amount() / winPool);
                payout(bet.bettorId(), currency, payout);
                Player p = Bukkit.getPlayer(bet.bettorId());
                if (p != null) p.sendMessage(ok("Your bet won! You received " + formatAmount(payout, currency) + "."));
            }
        }

        for (Bet bet : bets) {
            if (bet.sideIndex() == winningSideIndex) continue;
            Player p = Bukkit.getPlayer(bet.bettorId());
            if (p != null) {
                p.sendMessage(err("Your bet lost - " + formatAmount(bet.amount(), bet.currency()) + " gone."));
            }
        }
    }

    /** Refunds everyone when a match ends with no side actually deciding
     *  it (cancelled outright, both sides gone, etc) - nobody should lose
     *  a stake over a match that never really resolved. */
    public void cancelBets(MatchKind kind, UUID matchId) {
        List<Bet> bets = betsByMatch.remove(new MatchRef(kind, matchId));
        if (bets == null || bets.isEmpty()) return;
        for (Bet bet : bets) {
            payout(bet.bettorId(), bet.currency(), bet.amount());
            Player p = Bukkit.getPlayer(bet.bettorId());
            if (p != null) {
                p.sendMessage(ok("The match was cancelled - your bet of " + formatAmount(bet.amount(), bet.currency())
                        + " was refunded."));
            }
        }
    }

    /** Called from onDisable, before the server saves player data during
     *  shutdown - refunds every currently-placed bet across every match,
     *  regardless of kind. A real, confirmed risk without this: wagered
     *  currency is deducted from the bettor up front and held in an
     *  unresolved state until the match actually ends; if the server
     *  stops with any match still active, those bets would otherwise
     *  never resolve and that money would just be gone. payout() already
     *  works for a currently-offline player (Vault deposits and Honor
     *  adjustments don't require them to be online), so this is safe to
     *  run unconditionally for every pending bet. */
    public void refundAllOnShutdown() {
        for (MatchRef ref : new ArrayList<>(betsByMatch.keySet())) {
            cancelBets(ref.kind(), ref.matchId());
        }
    }

    private void payout(UUID playerId, BetCurrency currency, double amount) {
        if (currency == BetCurrency.VAULT) {
            vaultEconomy.deposit(Bukkit.getOfflinePlayer(playerId), amount);
        } else {
            playerDataManager.get(playerId).adjustHonorForBet((int) Math.round(amount));
            playerDataManager.save();
        }
    }

    private String formatAmount(double amount, BetCurrency currency) {
        return currency == BetCurrency.VAULT ? String.format("%.2f money", amount) : Math.round(amount) + " honor";
    }

    /** Human-readable label for a side - the player's name for a duel
     *  side (or "The Bot" for a bot opponent), or "Team N" for a war
     *  side. */
    public String sideName(MatchKind kind, UUID matchId, int sideIndex) {
        if (kind == MatchKind.DUEL) {
            DuelMatch match = duelManager.getActiveMatchById(matchId);
            if (match == null) return "Side " + (sideIndex + 1);
            if (sideIndex == 1 && match.isPlayer2Bot()) return "The Bot";
            UUID playerId = sideIndex == 0 ? match.getPlayer1() : match.getPlayer2();
            return nameOf(playerId);
        }
        return "Team " + (sideIndex + 1);
    }

    private String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName != null ? offlineName : playerId.toString().substring(0, 8);
    }

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
