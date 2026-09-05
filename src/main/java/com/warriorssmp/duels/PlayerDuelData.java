package com.warriorssmp.duels;

/**
 * Per-player persistent PvP stats. Honor is a spendable currency (goes down
 * when used to buy gear); lifetimeHonor only ever goes up, and is what rank
 * is calculated from - this way spending honor on gear never demotes you.
 */
public class PlayerDuelData {

    private int honor = 0;
    private int lifetimeHonor = 0;
    private int wins = 0;
    private int losses = 0;

    public int getHonor() {
        return honor;
    }

    public void setHonor(int honor) {
        this.honor = Math.max(0, honor);
    }

    public void addHonor(int amount) {
        this.honor = Math.max(0, this.honor + amount);
        if (amount > 0) {
            this.lifetimeHonor += amount;
        }
    }

    /** Adjusts spendable honor for a bet stake (negative) or payout
     *  (positive) WITHOUT touching lifetime honor - unlike addHonor, this
     *  never affects rank. Betting shouldn't let a player farm rank
     *  progress just by placing and winning bets; rank should only ever
     *  come from actually winning duels and wars. */
    public void adjustHonorForBet(int amount) {
        this.honor = Math.max(0, this.honor + amount);
    }

    public int getLifetimeHonor() {
        return lifetimeHonor;
    }

    public void setLifetimeHonor(int lifetimeHonor) {
        this.lifetimeHonor = lifetimeHonor;
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public void addWin() {
        this.wins++;
    }

    public int getLosses() {
        return losses;
    }

    public void setLosses(int losses) {
        this.losses = losses;
    }

    public void addLoss() {
        this.losses++;
    }

    public HonorRank getRank() {
        return HonorRank.forLifetimeHonor(lifetimeHonor);
    }

    public double getWinRate() {
        int total = wins + losses;
        return total == 0 ? 0.0 : (wins * 100.0) / total;
    }
}
