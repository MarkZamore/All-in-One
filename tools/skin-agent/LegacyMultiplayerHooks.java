package minecraft.portable.identity;

import java.lang.reflect.Field;

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
