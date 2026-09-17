package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.mojang.logging.LogUtils;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Writes a static HTML snapshot of the current diplomatic situation to
 *  "&lt;world save folder&gt;/ShadiomRP/diplomacy.html" - see the design spec. Regenerated after
 *  every action that changes what it would show (called explicitly from
 *  {@link FactionEventHandler}) and once on server start. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class DiplomacyReportWriter {

    private DiplomacyReportWriter() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        write(event.getServer());
    }

    static void write(MinecraftServer server) {
        try {
            FactionsData factions = FactionsData.get(server.overworld());
            DiplomacyData diplomacy = DiplomacyData.get(server.overworld());
            String html = buildHtml(factions, diplomacy);

            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("ShadiomRP");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("diplomacy.html"), html);
        } catch (IOException e) {
            LOGGER.warn("Failed to write the diplomacy report", e);
        }
    }

    private static String buildHtml(FactionsData factions, DiplomacyData diplomacy) {
        List<Faction> all = new ArrayList<>(factions.all());
        all.sort(Comparator.comparing(Faction::name, String.CASE_INSENSITIVE_ORDER));

        Map<String, List<String>> allies = new HashMap<>();
        Map<String, List<String>> wars = new HashMap<>();
        for (Map.Entry<String, DiplomacyData.Relation> entry : diplomacy.allRelations()) {
            String[] ids = entry.getKey().split("\\|", 2);
            if (ids.length != 2) continue;
            Faction a = factions.get(ids[0]);
            Faction b = factions.get(ids[1]);
            if (a == null || b == null) continue;
            Map<String, List<String>> bucket =
                    entry.getValue() == DiplomacyData.Relation.ALLY ? allies : wars;
            bucket.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(b.name());
            bucket.computeIfAbsent(b.id(), k -> new ArrayList<>()).add(a.name());
        }

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>Shadiom Diplomacy</title><style>")
                .append("body{font-family:sans-serif;background:#1b1b1b;color:#eee;padding:24px}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid #444;padding:8px;text-align:left}")
                .append("th{background:#2a2a2a}.ally{color:#7CFC7C}.war{color:#FF7C7C}.none{color:#888}")
                .append("</style></head><body>")
                .append("<h1>Shadiom Diplomacy</h1>")
                .append("<p>Generated ").append(Instant.now()).append("</p>")
                .append("<table><tr><th>Faction</th><th>Allies</th><th>At War With</th></tr>");

        for (Faction faction : all) {
            List<String> factionAllies = allies.get(faction.id());
            List<String> factionWars = wars.get(faction.id());
            html.append("<tr><td>").append(escape(faction.name())).append("</td>")
                    .append("<td class=\"").append(factionAllies == null ? "none" : "ally").append("\">")
                    .append(joinOrNone(factionAllies)).append("</td>")
                    .append("<td class=\"").append(factionWars == null ? "none" : "war").append("\">")
                    .append(joinOrNone(factionWars)).append("</td></tr>");
        }

        html.append("</table></body></html>");
        return html.toString();
    }

    private static String joinOrNone(List<String> names) {
        if (names == null || names.isEmpty()) return "None";
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(escape(names.get(i)));
        }
        return joined.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
