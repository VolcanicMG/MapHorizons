package com.volcanicmg.maphorizons.server;

import com.volcanicmg.maphorizons.Config;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** All FTB Teams code. Only loaded when {@code ftbteams} is installed. */
public final class FtbTeamsCompat {
    private FtbTeamsCompat() {
    }

    public static void init() {
        ExploredSync.teamSources = FtbTeamsCompat::sources;
        // Login after FTB has the player's team; plain PlayerLoggedInEvent can race it.
        TeamEvent.PLAYER_LOGGED_IN.register(e -> ExploredSync.requestFull(e.getPlayer().getUUID()));
        // Team events are rare; resyncing everyone online is simpler than tracking old and new teams.
        TeamEvent.PLAYER_CHANGED.register(e -> ExploredSync.requestFullForAll());
        TeamEvent.PLAYER_JOINED_PARTY.register(e -> ExploredSync.requestFullForAll());
        TeamEvent.PLAYER_LEFT_PARTY.register(e -> ExploredSync.requestFullForAll());
        TeamEvent.ADD_ALLY.register(e -> ExploredSync.requestFullForAll());
        TeamEvent.REMOVE_ALLY.register(e -> ExploredSync.requestFullForAll());
        TeamEvent.DELETED.register(e -> ExploredSync.requestFullForAll());
    }

    /** Team members (a solo player is their own player team), plus allies when enabled. */
    private static Set<UUID> sources(ServerPlayer viewer) {
        Set<UUID> out = new HashSet<>();
        out.add(viewer.getUUID());
        if (!FTBTeamsAPI.api().isManagerLoaded()) return out;
        Optional<Team> team = FTBTeamsAPI.api().getManager().getTeamForPlayer(viewer);
        if (team.isEmpty()) return out;
        out.addAll(team.get().getMembers());
        if (Config.SHARE_WITH_ALLIES.get())
            for (UUID p : ExploredData.get(viewer.server).players())
                if (team.get().getRankForPlayer(p).isAllyOrBetter()) out.add(p);
        return out;
    }
}
