package link.e4steam.steam;

import java.util.Collection;
import java.util.Collections;

/** The small exact-name surface used by the Forge 1.16.5 chat hook. */
public final class SteamRuntime {
    private static final SteamRuntime INSTANCE = new SteamRuntime();
    private final long localSteamId = 76561198000000001L;
    public final View view = new View();
    public final Bridge bridge = new Bridge();
    private final Registry bridgeRegistry = new Registry();

    public static SteamRuntime get() { return INSTANCE; }
    public View safeSessionView() { return view; }

    public static final class View {
        public String role = "GUEST";
        public String state = "ACTIVE";
        public boolean active() { return true; }
        public String roleCode() { return role; }
        public String stateCode() { return state; }
    }
    public final class Registry {
        Collection<Bridge> snapshot() { return Collections.singletonList(bridge); }
    }
    public static final class Bridge {
        public boolean closed;
        boolean isHostSide() { return false; }
        boolean isClosed() { return closed; }
        int localPort() { return 23002; }
        long remoteSteamId() { return 76561198000000002L; }
    }
}
