package com.zaremate.clockin;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod(ClockInMod.MOD_ID)
public final class ClockInMod {
    public static final String MOD_ID = "clockin";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String DATA_FILE = "clockin.json";

    private static final String PERMISSION_LOGIN = "clockin.login";
    private static final String PERMISSION_STATUS = "clockin.command.status";
    private static final String PERMISSION_IN = "clockin.command.in";
    private static final String PERMISSION_OUT = "clockin.command.out";
    private static final String PERMISSION_LEADERBOARD = "clockin.command.leaderboard";
    private static final String PERMISSION_INFO = "clockin.command.info";

    private static final Map<UUID, PlayerData> PLAYERS = new LinkedHashMap<>();
    private static Path dataFile;
    private static boolean dataLoaded;

    private static LuckPerms luckPerms;

    public ClockInMod(ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("ClockIn loaded.");
        ensureDataFile(container);
        loadData();
    }

    private void ensureDataFile(ModContainer container) {
        // The actual world directory is assigned lazily by the first server event.
        // This keeps initialization safe while the mod is constructed during bootstrap.
    }

    @SubscribeEvent
    public void onServerStarting(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        dataFile = event.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(DATA_FILE);
        loadData();
        dataLoaded = true;
    }

    @SubscribeEvent
    public void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        dataLoaded = false;
        dataFile = null;
        PLAYERS.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var clockIn = Commands.literal("in")
                .requires(source -> source.isPlayer() && hasPermission(ctxPlayer(source), PERMISSION_IN))
                .executes(ctx -> clockIn(ctx.getSource().getPlayerOrException()));

        var clockOut = Commands.literal("out")
                .requires(source -> source.isPlayer() && hasPermission(ctxPlayer(source), PERMISSION_OUT))
                .executes(ctx -> clockOut(ctx.getSource().getPlayerOrException(), false));

        var leaderboard = Commands.literal("leaderboard")
                .requires(source -> source.hasPermission(3) || hasPermission(ctxPlayer(source), PERMISSION_LEADERBOARD))
                .executes(ctx -> showLeaderboard(ctx.getSource()));

        var info = Commands.literal("info")
                .requires(source -> source.hasPermission(3) || hasPermission(ctxPlayer(source), PERMISSION_INFO))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ClockInMod::suggestPlayers)
                        .executes(ctx -> showPlayerInfo(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"))));

        var root = Commands.literal("clockin")
                .requires(source -> source.isPlayer()
                        && (source.hasPermission(3)
                        || hasPermission(ctxPlayer(source), PERMISSION_STATUS)))
                .executes(ctx -> showStatus(ctx.getSource().getPlayerOrException()))
                .then(clockIn)
                .then(clockOut)
                .then(leaderboard)
                .then(info);

        event.getDispatcher().register(root);
    }

    private static CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<net.minecraft.commands.CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

        Map<String, String> names = new LinkedHashMap<>();

        var server = context.getSource().getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String name = player.getGameProfile().getName();
                names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }

        for (PlayerData player : PLAYERS.values()) {
            if (player != null && player.name != null && !player.name.isBlank()) {
                names.putIfAbsent(player.name.toLowerCase(Locale.ROOT), player.name);
            }
        }

        names.values().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(remaining))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(builder::suggest);

        return builder.buildFuture();
    }

    private static Component prefix() {
        return Component.literal("ClockIn ")
                .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD)
                .append(Component.literal("» ")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    private ServerPlayer ctxPlayer(net.minecraft.commands.CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasPermission(ServerPlayer player, String permission) {
        if (player == null) return false;
        if (player.hasPermissions(3)) return true;

        try {
            if (luckPerms == null) {
                luckPerms = LuckPermsProvider.get();
            }

            var user = luckPerms.getUserManager().getUser(player.getUUID());
            return user != null
                    && user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        } catch (Throwable ex) {
            LOGGER.warn("Failed to check LuckPerms permission {} for {}.", permission, player.getGameProfile().getName(), ex);
            return false;
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        PlayerData data = getOrCreate(player);
        if (data.autoClockedOut) {
            player.sendSystemMessage(prefix()
                    .append(Component.literal("Your previous session was automatically closed because you left the server.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW)));
            data.autoClockedOut = false;
            saveData();
        }

        if (hasPermission(player, PERMISSION_LOGIN)) {
            player.sendSystemMessage(Component.empty());
            player.sendSystemMessage(Component.literal("✦ ADMIN TIME TRACKING")
                    .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
            player.sendSystemMessage(Component.literal("Track the time you spend providing administration assistance.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            player.sendSystemMessage(
                    Component.literal("[ CLOCK IN ]")
                            .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN).withBold(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/clockin in"))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Start your administration time session.")))));
            player.sendSystemMessage(
                    Component.literal("Use ")
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                            .append(Component.literal("/clockin")
                                    .withStyle(net.minecraft.ChatFormatting.AQUA))
                            .append(Component.literal(" to view your current status.")
                                    .withStyle(net.minecraft.ChatFormatting.GRAY)));
            player.sendSystemMessage(Component.empty());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        PlayerData data = getOrCreate(player);
        if (!data.clockedIn) return;

        long sessionSeconds = currentSessionSeconds(data);
        data.totalSeconds += sessionSeconds;
        data.clockedIn = false;
        data.clockInAt = 0L;
        data.autoClockedOut = true;
        data.name = player.getGameProfile().getName();
        saveData();

        LOGGER.info("Automatically clocked out {} after {}.", player.getGameProfile().getName(), formatDuration(sessionSeconds));
    }

    private int clockIn(ServerPlayer player) {
        PlayerData data = getOrCreate(player);
        if (data.clockedIn) {
            player.sendSystemMessage(prefix()
                    .append(Component.literal("You are already clocked in.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW)));
            return 0;
        }

        data.clockedIn = true;
        data.clockInAt = System.currentTimeMillis();
        data.autoClockedOut = false;
        saveData();

        player.sendSystemMessage(prefix()
                .append(Component.literal("Clocked in successfully.")
                        .withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD)));
        player.sendSystemMessage(Component.literal("Administration time tracking has started.")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        return 1;
    }

    private int clockOut(ServerPlayer player, boolean automatic) {
        PlayerData data = getOrCreate(player);
        if (!data.clockedIn) {
            player.sendSystemMessage(prefix()
                    .append(Component.literal("You are not currently clocked in.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW)));
            return 0;
        }

        long sessionSeconds = currentSessionSeconds(data);
        data.totalSeconds += sessionSeconds;
        data.clockedIn = false;
        data.clockInAt = 0L;
        data.autoClockedOut = automatic;
        saveData();

        if (automatic) {
            player.sendSystemMessage(prefix()
                    .append(Component.literal("Session closed automatically.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD)));
        } else {
            player.sendSystemMessage(prefix()
                    .append(Component.literal("Clocked out successfully.")
                            .withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD)));
            player.sendSystemMessage(
                    Component.literal("  Session: ")
                            .withStyle(net.minecraft.ChatFormatting.GRAY)
                            .append(Component.literal(formatDuration(sessionSeconds))
                                    .withStyle(net.minecraft.ChatFormatting.AQUA, net.minecraft.ChatFormatting.BOLD)));
            player.sendSystemMessage(
                    Component.literal("  Total: ")
                            .withStyle(net.minecraft.ChatFormatting.GRAY)
                            .append(Component.literal(formatDuration(data.totalSeconds))
                                    .withStyle(net.minecraft.ChatFormatting.AQUA, net.minecraft.ChatFormatting.BOLD)));
        }

        return 1;
    }

    private int showStatus(ServerPlayer player) {
        PlayerData data = getOrCreate(player);
        long total = totalSeconds(data);

        player.sendSystemMessage(Component.literal("══════════════════════════════════════")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        player.sendSystemMessage(Component.literal("✦ ADMIN CLOCK-IN")
                .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
        player.sendSystemMessage(
                Component.literal("  Status: ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(Component.literal(data.clockedIn ? "CLOCKED IN" : "CLOCKED OUT")
                                .withStyle(data.clockedIn
                                        ? net.minecraft.ChatFormatting.GREEN
                                        : net.minecraft.ChatFormatting.RED,
                                        net.minecraft.ChatFormatting.BOLD)));

        if (data.clockedIn) {
            player.sendSystemMessage(
                    Component.literal("  Session: ")
                            .withStyle(net.minecraft.ChatFormatting.GRAY)
                            .append(Component.literal(formatDuration(currentSessionSeconds(data)))
                                    .withStyle(net.minecraft.ChatFormatting.AQUA)));
        }

        player.sendSystemMessage(
                Component.literal("  Total:   ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(Component.literal(formatDuration(total))
                                .withStyle(net.minecraft.ChatFormatting.AQUA)));

        player.sendSystemMessage(Component.empty());

        if (data.clockedIn) {
            player.sendSystemMessage(Component.literal("[ CLOCK OUT ]")
                    .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.RED).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/clockin out"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("End your current administration time session.")))));
        } else {
            player.sendSystemMessage(Component.literal("[ CLOCK IN ]")
                    .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/clockin in"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Start an administration time session.")))));
        }

        player.sendSystemMessage(Component.literal("══════════════════════════════════════")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return 1;
    }

    private int showLeaderboard(net.minecraft.commands.CommandSourceStack source) {
        List<PlayerData> entries = new ArrayList<>(PLAYERS.values());
        entries.sort(Comparator.comparingLong((PlayerData p) -> totalSeconds(p)).reversed()
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));

        source.sendSuccess(() -> Component.literal("══════════════════════════════════════")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("✦ ADMIN CLOCK-IN LEADERBOARD")
                .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD), false);

        int limit = Math.min(10, entries.size());
        for (int i = 0; i < limit; i++) {
            final int rank = i + 1;
            PlayerData p = entries.get(i);
            source.sendSuccess(() -> Component.literal(String.format("%2d. ", rank))
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                            .append(Component.literal(p.name)
                                    .withStyle(net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.BOLD))
                            .append(Component.literal("  " + formatDuration(totalSeconds(p)))
                                    .withStyle(net.minecraft.ChatFormatting.AQUA)),
                    false);
        }

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No administration time has been recorded yet.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
        }

        source.sendSuccess(() -> Component.literal("══════════════════════════════════════")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private int showPlayerInfo(net.minecraft.commands.CommandSourceStack source, String targetName) {
        PlayerData data = findByName(targetName);
        if (data == null) {
            source.sendFailure(prefix()
                    .append(Component.literal("No clock-in data found for ")
                            .withStyle(net.minecraft.ChatFormatting.GRAY))
                    .append(Component.literal(targetName)
                            .withStyle(net.minecraft.ChatFormatting.WHITE))
                    .append(Component.literal(".")
                            .withStyle(net.minecraft.ChatFormatting.GRAY)));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("══════════════════════════════════════")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("✦ CLOCK-IN INFO")
                .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("  Player:  ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(Component.literal(data.name)
                                .withStyle(net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.BOLD)), false);
        source.sendSuccess(() -> Component.literal("  Status:  ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(Component.literal(data.clockedIn ? "CLOCKED IN" : "CLOCKED OUT")
                                .withStyle(data.clockedIn
                                        ? net.minecraft.ChatFormatting.GREEN
                                        : net.minecraft.ChatFormatting.RED,
                                        net.minecraft.ChatFormatting.BOLD)), false);
        source.sendSuccess(() -> Component.literal("  Total:   ")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(Component.literal(formatDuration(totalSeconds(data)))
                                .withStyle(net.minecraft.ChatFormatting.AQUA)), false);
        if (data.clockedIn) {
            source.sendSuccess(() -> Component.literal("  Session: ")
                    .withStyle(net.minecraft.ChatFormatting.GRAY)
                    .append(Component.literal(formatDuration(currentSessionSeconds(data)))
                            .withStyle(net.minecraft.ChatFormatting.AQUA)), false);
        }
        source.sendSuccess(() -> Component.literal("══════════════════════════════════════")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private PlayerData getOrCreate(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerData data = PLAYERS.get(uuid);
        if (data == null) {
            data = new PlayerData(player.getGameProfile().getName());
            PLAYERS.put(uuid, data);
            saveData();
        } else {
            data.name = player.getGameProfile().getName();
        }
        return data;
    }

    private PlayerData findByName(String name) {
        return PLAYERS.entrySet().stream()
                .filter(e -> e.getValue().name.equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private long currentSessionSeconds(PlayerData data) {
        if (!data.clockedIn || data.clockInAt <= 0) return 0;
        return Math.max(0, (System.currentTimeMillis() - data.clockInAt) / 1000L);
    }

    private long totalSeconds(PlayerData data) {
        return Math.max(0, data.totalSeconds + currentSessionSeconds(data));
    }

    private static String formatDuration(long seconds) {
        seconds = Math.max(0, seconds);
        Duration d = Duration.ofSeconds(seconds);
        long days = d.toDays();
        long hours = d.minusDays(days).toHours();
        long minutes = d.minusDays(days).minusHours(hours).toMinutes();
        long secs = d.getSeconds() % 60;
        if (days > 0) return days + "d " + String.format("%02dh %02dm %02ds", hours, minutes, secs);
        if (hours > 0) return hours + "h " + String.format("%02dm %02ds", minutes, secs);
        if (minutes > 0) return minutes + "m " + String.format("%02ds", secs);
        return secs + "s";
    }

    private void loadData() {
        if (dataFile == null) return;
        dataLoaded = false;
        try {
            if (!Files.exists(dataFile)) {
                saveData();
                return;
            }

            String json = Files.readString(dataFile);
            // Lightweight parser for the simple KubeJS-compatible file schema.
            // Prefer Gson from NeoForge's runtime if available.
            var gsonClass = Class.forName("com.google.gson.Gson");
            Object gson = gsonClass.getConstructor().newInstance();
            Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
            Object root = fromJson.invoke(gson, json, ClockInFile.class);
            if (root instanceof ClockInFile file && file.players != null) {
                PLAYERS.clear();
                PLAYERS.putAll(file.players);
            }
            LOGGER.info("Loaded {} ClockIn player record(s).", PLAYERS.size());
            dataLoaded = true;
        } catch (Throwable ex) {
            LOGGER.error("Failed to load ClockIn data.", ex);
            dataLoaded = true;
        }
    }

    private void saveData() {
        if (dataFile == null) return;
        try {
            Files.createDirectories(dataFile.getParent());
            var gsonClass = Class.forName("com.google.gson.GsonBuilder");
            Object builder = gsonClass.getConstructor().newInstance();
            builder = gsonClass.getMethod("setPrettyPrinting").invoke(builder);
            Object gson = builder.getClass().getMethod("create").invoke(builder);

            var wrapper = new ClockInFile();
            wrapper.version = 1;
            wrapper.players = PLAYERS;

            String json = (String) gson.getClass().getMethod("toJson", Object.class).invoke(gson, wrapper);
            Files.writeString(dataFile, json);
        } catch (Throwable ex) {
            LOGGER.error("Failed to save ClockIn data.", ex);
        }
    }

    public static final class ClockInFile {
        public int version = 1;
        public Map<UUID, PlayerData> players = new LinkedHashMap<>();
    }

    public static final class PlayerData {
        public String name;
        public long totalSeconds;
        public boolean clockedIn;
        public long clockInAt;
        public boolean autoClockedOut;

        public PlayerData() {}

        public PlayerData(String name) {
            this.name = name;
        }
    }
}
