package com.tirener.shadiom.shadiomrpoverhaul.names;

import java.util.List;

/**
 * The name pool every consuming mod gets for free, so a server running ShadiomRPOverhaul alone
 * still has something sensible to offer in the name picker. Consuming mods can still add their
 * own names and filters through {@link ShadiomNameAPI} on top of this baseline.
 */
public final class DefaultNames {

    private DefaultNames() {}

    private static final List<String> FIRST_NAMES = List.of(
            "Kael", "Mira", "Soren", "Lira", "Doran", "Ashe", "Wren", "Talon",
            "Rhys", "Selene", "Corin", "Isolde", "Bram", "Fenn", "Nadia", "Varek",
            "Elowen", "Thane", "Rowan", "Sable", "Calder", "Ysolde", "Garrick", "Thessaly",
            "Kestrel", "Marek", "Odile", "Percival", "Ravenna", "Silas", "Tamsin", "Zephyr",
            "Aldric", "Briar", "Dorian", "Freya", "Gideon", "Hollis", "Ines", "Jareth"
    );

    private static final List<String> SURNAMES = List.of(
            "Voss", "Draven", "Thorne", "Marrow", "Rook", "Ember", "Cael", "Blackwood",
            "Hollow", "Ashgrave", "Sorrow", "Crane", "Vale", "Winters", "Stormrunner", "Nightshade",
            "Frost", "Cinder", "Wolfe", "Sable", "Grimm", "Ashborn", "Ravensworth", "Duskwood",
            "Ironwood", "Moonshade", "Blackthorn", "Graywater", "Sable", "Crowley", "Hallow", "Wren"
    );

    public static void register() {
        FIRST_NAMES.forEach(ShadiomNameAPI::registerFirstName);
        SURNAMES.forEach(ShadiomNameAPI::registerSurname);
    }
}
