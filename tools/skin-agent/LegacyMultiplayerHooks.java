package minecraft.portable.identity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.UUID;

/** Bootstrap-visible calls from the exact Forge and Simple RPC classes in this pack. */
public final class LegacyMultiplayerHooks {
    private LegacyMultiplayerHooks() { }

    public static boolean enableMultiplayerMenu(boolean allowedByMinecraft, Object minecraft) {
        if (allowedByMinecraft) return true;
        try {
            // Keep the explicit --disableMultiplayer launch option. Steam uses
            // a LANMinecraft identity without the Microsoft social service.
            Field enabled = minecraft.getClass().getDeclaredField("field_238175_ae_");
            enabled.setAccessible(true);
            return enabled.getBoolean(minecraft);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return false;
        }
    }

    public static boolean allowSteamGuestChat(boolean allowedByMinecraft, Object minecraft) {
        if (allowedByMinecraft) return true;
        try {
            if (!usesFullChat(minecraft)) return false;

            Object handler = minecraft.getClass().getMethod("func_147114_u").invoke(minecraft);
            if (handler == null) return false;
            Object network = handler.getClass().getMethod("func_147298_b").invoke(handler);
            Object remote = network.getClass().getMethod("func_74430_c").invoke(network);
            if (!(remote instanceof InetSocketAddress)) return false;
            InetSocketAddress address = (InetSocketAddress) remote;
            if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) return false;

            Object runtime = steamRuntime(minecraft);
            if (activeView(runtime, "GUEST") == null || !usesLauncherSession(minecraft, runtime)) return false;

            // A direct server on loopback can be active while a Steam bridge is closing.
            // Match the current play socket to an open client bridge in this Steam session.
            Object bridges = field(runtime, "bridgeRegistry");
            for (Object bridge : (Collection<?>) declared(bridges, "snapshot")) {
                if (Boolean.FALSE.equals(declared(bridge, "isHostSide")) && Boolean.FALSE.equals(declared(bridge, "isClosed"))
                        && Integer.valueOf(address.getPort()).equals(declared(bridge, "localPort"))
                        && ((Long) declared(bridge, "remoteSteamId")) != 0L) return true;
            }
        } catch (ReflectiveOperationException | NoSuchAlgorithmException | RuntimeException | LinkageError unavailable) {
            return false;
        }
        return false;
    }

    /** Selects the normal FilterManager path only for an authenticated peer of this private host. */
    public static boolean allowSteamHostChat(boolean allowedByMinecraft, Object minecraft, UUID sender) {
        if (allowedByMinecraft) return true;
        if (sender == null) return false;
        try {
            if (!usesFullChat(minecraft) || !Boolean.TRUE.equals(declared(minecraft, "func_71387_A"))) return false;
            Object runtime = steamRuntime(minecraft);
            Object view = activeView(runtime, "INTEGRATED_HOST");
            if (view == null || !usesLauncherSession(minecraft, runtime)) return false;

            UUID portableSender = PortableIdentityProfiles.remap(sender, null);
            for (Object peer : (Collection<?>) declared(view, "peers")) {
                UUID tunnelId = (UUID) declared(peer, "minecraftUuid");
                if (tunnelId != null && portableSender.equals(PortableIdentityProfiles.remap(tunnelId, null))) return true;
            }
        } catch (ReflectiveOperationException | NoSuchAlgorithmException | RuntimeException | LinkageError unavailable) {
            return false;
        }
        return false;
    }

    private static boolean usesFullChat(Object minecraft) throws ReflectiveOperationException {
        if (!Boolean.TRUE.equals(field(minecraft, "field_238176_af_"))) return false;
        Object visibility = field(field(minecraft, "field_71474_y"), "field_74343_n");
        return visibility instanceof Enum<?> && "FULL".equals(((Enum<?>) visibility).name());
    }

    private static Object steamRuntime(Object minecraft) throws ReflectiveOperationException {
        Class<?> runtime = Class.forName("link.e4steam.steam.SteamRuntime", false, minecraft.getClass().getClassLoader());
        return runtime.getMethod("get").invoke(null);
    }

    private static Object activeView(Object runtime, String role) throws ReflectiveOperationException {
        Object view = declared(runtime, "safeSessionView");
        return Boolean.TRUE.equals(declared(view, "active")) && role.equals(declared(view, "roleCode"))
            && "ACTIVE".equals(declared(view, "stateCode")) ? view : null;
    }

    private static boolean usesLauncherSession(Object minecraft, Object runtime)
            throws ReflectiveOperationException, NoSuchAlgorithmException {
        Object session = declared(minecraft, "func_110432_I");
        return isLauncherSession((String) declared(session, "func_148255_b"),
            (String) declared(session, "func_111285_a"), (String) declared(session, "func_148254_d"),
            (Long) field(runtime, "localSteamId"));
    }

    // LANMinecraft generates this local marker for its offline MSession. A Minecraft
    // account token still uses the native Authlib chat decision, even on a Steam server.
    static boolean isLauncherSession(String uuid, String name, String token, long steamId)
            throws NoSuchAlgorithmException {
        if (steamId == 0L || name == null || name.isEmpty() || token == null || token.length() != 40) return false;
        if (uuid != null && uuid.matches("[0-9a-fA-F]{32}")) {
            uuid = uuid.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
        }
        String seed = "MinecraftPortableLocalSession:v2|" + UUID.fromString(uuid) + "|" + Long.toUnsignedString(steamId) + "|" + name;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        char[] marker = new char[40];
        for (int index = 0; index < 20; index++) {
            marker[2 * index] = Character.forDigit(Byte.toUnsignedInt(hash[index]) >>> 4, 16);
            marker[2 * index + 1] = Character.forDigit(hash[index] & 15, 16);
        }
        return MessageDigest.isEqual(new String(marker).getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object declared(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    public static void pingForMaximum(Object pinger, Object server, Runnable finished, String presence) throws Exception {
        // Current and exclusive player counts already use the play connection.
        if (!presence.contains("%maxplayers%")) return;
        ClassLoader loader = pinger.getClass().getClassLoader();
        Class<?> serverType = Class.forName("net.minecraft.client.multiplayer.ServerData", false, loader);
        String address = (String) serverType.getField("field_78845_b").get(server);
        if (address == null || isSteamEndpoint(address, loader)) return;
        pinger.getClass().getMethod("func_147224_a", serverType, Runnable.class).invoke(pinger, server, finished);
    }

    public static String populationText(Object population) throws Exception {
        if (population == null) return "0/0";
        Class<?> component = Class.forName("net.minecraft.util.text.ITextComponent", false,
            population.getClass().getClassLoader());
        return (String) component.getMethod("getString").invoke(population);
    }

    private static boolean isSteamEndpoint(String address, ClassLoader loader) {
        int colon = address.indexOf(':');
        String host = (colon < 0 ? address : address.substring(0, colon)).trim();
        return host.endsWith(".steam") || ("127.0.0.1".equals(host) && isSteamGuest(loader));
    }

    private static boolean isSteamGuest(ClassLoader loader) {
        try {
            Class<?> runtimeType = Class.forName("link.e4steam.steam.SteamRuntime", false, loader);
            Object runtime = runtimeType.getMethod("get").invoke(null);
            Object view = runtimeType.getMethod("safeSessionView").invoke(runtime);
            if (!Boolean.TRUE.equals(view.getClass().getMethod("active").invoke(view))) return false;
            Object role = view.getClass().getMethod("roleCode").invoke(view);
            return "GUEST".equals(role) || "DEDICATED_SERVER_CLIENT".equals(role);
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }
}
