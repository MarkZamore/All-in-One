import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.UUID;
import java.util.jar.JarFile;
import link.e4steam.steam.SteamRuntime;
import minecraft.portable.identity.LegacyMultiplayerHooks;
import minecraft.portable.identity.LegacySkinAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Checks the exact SRG chat call site and the full scoped decision on Java 8. */
public final class VerifySteamChat {
    private static final String CLIENT = "net/minecraft/client/Minecraft";

    public static void main(String[] args) throws Exception {
        try (JarFile client = new JarFile(Paths.get(args[0]).toFile());
                InputStream stream = client.getInputStream(client.getJarEntry(CLIENT + ".class"))) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            for (int read; (read = stream.read(block)) != -1;) buffer.write(block, 0, read);
            byte[] source = buffer.toByteArray();
            LegacySkinAgent agent = new LegacySkinAgent();
            System.clearProperty("link.e4steam.launcherManagedLan");
            require(agent.transform(null, CLIENT, null, null, source) == null, "Unmanaged client changed");
            System.setProperty("link.e4steam.launcherManagedLan", "true");
            byte[] patched = agent.transform(null, CLIENT, null, null, source);
            require(patched != null, "The stock chat screen method was not matched");
            verifyChatCall(patched);
        }

        Client client = new Client();
        SteamRuntime runtime = SteamRuntime.get();
        require(LegacyMultiplayerHooks.allowSteamGuestChat(true, null), "Vanilla permission changed");
        require(LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Managed Steam guest was blocked");
        client.field_238176_af_ = false;
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Explicit disableChat was ignored");
        client.field_238176_af_ = true;
        client.field_71474_y.field_74343_n = Visibility.HIDDEN;
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Hidden chat was changed");
        client.field_71474_y.field_74343_n = Visibility.FULL;

        runtime.view.role = "DEDICATED_SERVER_CLIENT";
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Dedicated session was allowed");
        runtime.view.role = "GUEST";
        runtime.view.state = "CLOSING";
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Closing session was allowed");
        runtime.view.state = "ACTIVE";
        runtime.bridge.closed = true;
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Stale bridge was allowed");
        runtime.bridge.closed = false;
        client.handler.network.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 23003);
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Unrelated loopback socket was allowed");
        client.handler.network.address = new InetSocketAddress("192.0.2.1", 23002);
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Direct IP was allowed");
        client.handler.network.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 23002);
        String portableToken = client.session.token;
        client.session.token = "unrelatedsynthetic-token-for-negative-case";
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Unrelated token was allowed");
        client.session.token = "0123456789abcdef0123456789abcdef01234567";
        require(!LegacyMultiplayerHooks.allowSteamGuestChat(false, client), "Different 40-character token was allowed");
        UUID guest = UUID.fromString("12345678-90ab-4cd8-a236-7bcb624d839f");
        UUID stranger = UUID.fromString("e2345678-90ab-4cd8-a236-7bcb624d839f");
        runtime.view.role = "INTEGRATED_HOST";
        runtime.view.peers = Collections.singletonList(new SteamRuntime.Peer(guest));
        client.integrated = true;
        require(!LegacyMultiplayerHooks.allowSteamHostChat(false, client, guest), "Host with non-portable token accepted a peer");
        client.session.token = portableToken;
        require(LegacyMultiplayerHooks.allowSteamHostChat(false, client, guest), "Active private host filtered its Steam peer");
        require(!LegacyMultiplayerHooks.allowSteamHostChat(false, client, stranger), "Unrelated sender was allowed");
        client.integrated = false;
        require(!LegacyMultiplayerHooks.allowSteamHostChat(false, client, guest), "A non-local host was allowed");
        require(LegacyMultiplayerHooks.allowSteamHostChat(true, null, stranger), "Vanilla receive permission changed");
        System.out.println("PASS exact Forge chat call, scope, launcher marker, Steam bridge, and negative controls");
    }

    private static void verifyChatCall(byte[] patched) {
        int[] count = new int[4];
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                        if ("allowSteamGuestChat".equals(name)) {
                            require("func_238207_b_".equals(method), "Another native path was patched");
                            count[0]++;
                        }
                        if ("func_238207_b_".equals(method) && "func_238217_s_".equals(name)) count[1]++;
                        if ("allowSteamHostChat".equals(name)) {
                            require("func_238198_a_".equals(method), "Another incoming path was patched");
                            count[2]++;
                        }
                        if ("func_238198_a_".equals(method) && "func_244756_c".equals(name)) count[3]++;
                    }
                };
            }
        }, 0);
        require(count[0] == 1 && count[1] == 1 && count[2] == 1 && count[3] == 1,
            "Expected the ordinary chat screen call and unchanged native mute filter");
    }

    public enum Visibility { FULL, HIDDEN }
    public static final class Settings { public Visibility field_74343_n = Visibility.FULL; }
    public static final class Client {
        private boolean field_238176_af_ = true;
        private final Settings field_71474_y = new Settings();
        public final Handler handler = new Handler();
        public final Session session = new Session();
        private boolean integrated;
        public Handler func_147114_u() { return handler; }
        public Session func_110432_I() { return session; }
        public boolean func_71387_A() { return integrated; }
    }
    public static final class Handler {
        public final Network network = new Network();
        public Network func_147298_b() { return network; }
    }
    public static final class Network {
        public SocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 23002);
        public SocketAddress func_74430_c() { return address; }
    }
    public static final class Session {
        public String token = "db509cf28289e0bd28a666c290e4d037f69d8d53";
        public String func_148255_b() { return "1234567890ab4cd8a2367bcb624d839f"; }
        public String func_111285_a() { return "FixturePlayer"; }
        public String func_148254_d() { return token; }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
