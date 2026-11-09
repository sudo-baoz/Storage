package net.danh.storage.Manager;

import net.danh.storage.Storage;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;

/**
 * A way to pay for upgrades. Vault and PlayerPoints are independent: a missing one only disables the levels that
 * use it. Tests register their own implementation through {@link #register}.
 */
public interface CurrencyProvider {

    boolean available();

    /**
     * Current balance, or -1 when it cannot be read.
     */
    double balance(Player player);

    /**
     * Takes the amount; true only when the economy confirms the charge.
     */
    boolean withdraw(Player player, double amount);

    /**
     * Gives the amount back (refund after a failed save).
     */
    boolean deposit(Player player, double amount);

    enum Currency {VAULT, PLAYERPOINTS}

    Map<Currency, CurrencyProvider> PROVIDERS = new EnumMap<>(Currency.class);

    static CurrencyProvider of(Currency currency) {
        CurrencyProvider registered = PROVIDERS.get(currency);
        return registered != null ? registered : currency == Currency.VAULT ? VAULT : POINTS;
    }

    static void register(Currency currency, CurrencyProvider provider) {
        if (provider == null) {
            PROVIDERS.remove(currency);
        } else {
            PROVIDERS.put(currency, provider);
        }
    }

    CurrencyProvider VAULT = new CurrencyProvider() {
        @Override
        public boolean available() {
            return Storage.isVaultHooked();
        }

        @Override
        public double balance(Player player) {
            return Storage.getVaultBalance(player);
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            return Storage.withdrawFromVault(player, amount);
        }

        @Override
        public boolean deposit(Player player, double amount) {
            return amount <= 0 || Storage.depositToVault(player, amount);
        }
    };

    CurrencyProvider POINTS = new CurrencyProvider() {
        @Override
        public boolean available() {
            return Storage.isPlayerPointsHooked();
        }

        @Override
        public double balance(Player player) {
            return Storage.getPlayerPointsBalance(player);
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            return Storage.withdrawFromPlayerPoints(player, (int) amount);
        }

        @Override
        public boolean deposit(Player player, double amount) {
            return amount <= 0 || Storage.depositToPlayerPoints(player, amount);
        }
    };
}
