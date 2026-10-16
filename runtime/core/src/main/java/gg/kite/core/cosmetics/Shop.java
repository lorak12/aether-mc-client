package gg.kite.core.cosmetics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The in-game store: what the backend sells (GET /v1/store), the coin balance and buying (POST /v1/store/buy) with
 * the same coins as the launcher's store. Requests run on the sync worker; the menu reads the latest state and polls
 * {@link #poll()} for results to show. Admin accounts (backend/src/admins.ts) report {@link #unlimited()}.
 */
public final class Shop {
    /** One item for sale (or free) in the backend's catalog. */
    public static final class Offer {
        public final String id, name, description;
        public final Slot slot;
        public final Cosmetic.Rarity rarity;
        /** Coins, or -1 for free items. */
        public final long price;
        /** Published by an admin from the importer (can be withdrawn); who published it. */
        public final boolean custom;
        public final String author;

        Offer(String id, String name, String description, Slot slot, Cosmetic.Rarity rarity, long price, boolean custom, String author) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.slot = slot;
            this.rarity = rarity;
            this.price = price;
            this.custom = custom;
            this.author = author;
        }

        public boolean free() { return price < 0; }
    }

    /** A finished request, for a flash in the menu. */
    public static final class Event {
        public final String message, boughtId;
        /** Set when an admin's publish went through. */
        public final String publishedId;
        public final boolean ok;

        Event(String message, boolean ok, String boughtId) { this(message, ok, boughtId, null); }

        Event(String message, boolean ok, String boughtId, String publishedId) {
            this.message = message;
            this.ok = ok;
            this.boughtId = boughtId;
            this.publishedId = publishedId;
        }
    }

    private final Cosmetics owner;
    private volatile List<Offer> offers;
    private volatile long coins = -1;
    private volatile boolean unlimited, loading;
    private volatile String buying, error;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<Event>();

    Shop(Cosmetics owner) { this.owner = owner; }

    /** Whether there is a backend to buy from at all. */
    public boolean available() { return owner.sync() != null; }

    /** Items for sale, null until the first answer. */
    public List<Offer> offers() { return offers; }

    /** Balance; -1 while unknown (offline, not signed in yet). */
    public long coins() { return coins; }

    /** Admin account: buying never runs out of coins. */
    public boolean unlimited() { return unlimited; }

    /** Admin account (backend/src/admins.ts): may import and publish cosmetics. Same list as unlimited coins. */
    public boolean admin() { return unlimited; }

    private long lastAuto;
    private volatile boolean publishing;

    /** Client tick: keep the catalog (and the published cosmetics) fresh even when the shop isn't open. */
    void autoRefresh(long now) {
        if (!available() || loading) return;
        long every = offers == null ? 30_000 : 5 * 60_000;
        if (now - lastAuto < every) return;
        lastAuto = now;
        refresh();
    }

    public boolean publishing() { return publishing; }

    /**
     * Publish to the store (admins): {@code bundle} is the POST /v1/admin/cosmetics body. The result arrives through
     * {@link #poll()}; on success the catalog refreshes, which downloads it like any other published cosmetic.
     */
    public void publish(Map<String, Object> bundle) {
        CosmeticsSync s = owner.sync();
        if (s == null) {
            events.add(new Event("Publishing needs the Kite backend", false, null));
            return;
        }
        if (publishing) return;
        publishing = true;
        s.publish(this, gg.kite.core.Json.write(bundle));
    }

    /** Take a published cosmetic out of the store (admins). */
    public void unpublish(String id) {
        CosmeticsSync s = owner.sync();
        if (s != null) s.unpublish(this, id);
    }

    void published(String id, String error) {
        publishing = false;
        if (error != null) {
            events.add(new Event("Couldn't publish: " + error, false, null));
            return;
        }
        events.add(new Event("Published to the store: everyone can buy it now", true, null, id));
        loading = false;
        refresh();
    }

    void unpublished(String id, String error) {
        events.add(new Event(error == null ? "Taken out of the store" : "Couldn't remove it: " + error, error == null, null));
        if (error == null) refresh();
    }

    public boolean loading() { return loading; }

    /** Id being bought right now, or null. */
    public String buying() { return buying; }

    /** Why the last refresh failed, or null. */
    public String error() { return error; }

    /** Results since the last call (client thread). */
    public Event poll() { return events.poll(); }

    /** Ask the backend for the catalog and balance again (no-op while a refresh runs or offline). */
    public void refresh() {
        CosmeticsSync s = owner.sync();
        if (s == null || loading) return;
        loading = true;
        s.fetchStore(this);
    }

    /** Buy {@code id}; the result arrives through {@link #poll()}. */
    public void buy(String id) {
        CosmeticsSync s = owner.sync();
        if (s == null) {
            events.add(new Event("The shop needs the Kite backend: start the game from the Kite launcher", false, null));
            return;
        }
        if (buying != null) return;
        buying = id;
        s.buy(this, id);
    }

    /** Whether {@code price} coins can be spent now (unknown balances let the backend decide). */
    public boolean affordable(long price) { return unlimited || coins < 0 || coins >= price; }

    /** The offer for a cosmetic id, or null. */
    public Offer offer(String id) {
        List<Offer> o = offers;
        if (o == null || id == null) return null;
        for (Offer x : o) if (x.id.equals(id)) return x;
        return null;
    }

    // ---- worker thread ----------------------------------------------------------------------------------------------

    void wallet(Object coinsJson, Object unlimitedJson) {
        unlimited = Boolean.TRUE.equals(unlimitedJson);
        if (coinsJson instanceof Number) coins = ((Number) coinsJson).longValue();
    }

    void storeLoaded(Map<String, Object> json) {
        List<Offer> out = new ArrayList<Offer>();
        if (json.get("items") instanceof List) {
            for (Object o : (List<?>) json.get("items")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                Slot slot = Slot.byId(String.valueOf(m.get("slot")));
                if (slot == null || !(m.get("id") instanceof String)) continue;
                long price = m.get("price") instanceof Number ? ((Number) m.get("price")).longValue() : -1;
                out.add(new Offer((String) m.get("id"), String.valueOf(m.get("name")), m.get("description") instanceof String ? (String) m.get("description") : "",
                        slot, Cosmetic.Rarity.byId(String.valueOf(m.get("rarity"))), price, Boolean.TRUE.equals(m.get("custom")),
                        m.get("author") instanceof String ? (String) m.get("author") : ""));
            }
        }
        offers = Collections.unmodifiableList(out);
        owner.setOwned(new java.util.HashSet<String>(CosmeticsSync.strings(json.get("owned"))));
        wallet(json.get("coins"), json.get("unlimited"));
        error = null;
        loading = false;
    }

    void storeFailed(String why) {
        error = why;
        loading = false;
    }

    void bought(String id, Map<String, Object> json) {
        owner.setOwned(new java.util.HashSet<String>(CosmeticsSync.strings(json.get("owned"))));
        wallet(json.get("coins"), json.get("unlimited"));
        Offer o = offer(id);
        buying = null;
        events.add(new Event((o == null ? id : o.name) + " is yours", true, id));
    }

    void buyFailed(String id, String error) {
        buying = null;
        String msg;
        switch (error) {
            case "insufficient_coins": msg = "Not enough coins: top up in the Kite launcher"; break;
            case "already_owned": msg = "You already own that"; break;
            case "not_for_sale": msg = "That one isn't for sale"; break;
            case "unauthorized": msg = "Sign in through the Kite launcher to buy"; break;
            default: msg = "Couldn't buy it (" + error + ")"; break;
        }
        events.add(new Event(msg, false, null));
    }
}
