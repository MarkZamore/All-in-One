import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.InsecureTextureException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Runs the packaged premain and the exact Authlib session service on Java 8. */
public final class VerifySteamSkins {
    private static final UUID HOST = uuid(1);
    private static final UUID GUEST = uuid(2);
    private static final UUID HOST_TUNNEL = uuid(11);
    private static final UUID GUEST_TUNNEL = uuid(12);
    private static final UUID CONFLICTED_TUNNEL = uuid(13);
    private static final String MOJANG = "https://textures.minecraft.net/texture/official-test";

    public static void main(String[] args) throws Exception {
        require("1.8".equals(System.getProperty("java.specification.version")), "Use the pack's Java 8");
        Path proof = Paths.get(args[0]);
        boolean baseline = "baseline".equals(args[1]);
        Path skins = proof.resolve("skin-profiles.properties");
        Path identities = proof.resolve("identity-profiles.properties");
        System.setProperty("minecraft.portable.skin.registry", skins.toString());
        System.setProperty("minecraft.portable.identity.registry", identities.toString());

        MinecraftSessionService service = new YggdrasilAuthenticationService(Proxy.NO_PROXY).createMinecraftSessionService();
        byte[] hostSkin = png(0xFFFF0000);
        byte[] guestSkin = png(0xFF00FF00);
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String hostUrl = register(endpoint, HOST, hostSkin);
        String guestUrl = register(endpoint, GUEST, guestSkin);
        endpoint.start();
        try {
            Files.write(skins, Arrays.asList(row(HOST, hostUrl, "classic"), row(GUEST, guestUrl, "slim")), StandardCharsets.UTF_8);
            Files.write(identities, Arrays.asList(
                identity("Host", HOST, HOST_TUNNEL),
                identity("AmbiguousA", HOST, CONFLICTED_TUNNEL),
                identity("AmbiguousB", GUEST, CONFLICTED_TUNNEL)), StandardCharsets.UTF_8);

            GameProfile coldGuest = new GameProfile(GUEST_TUNNEL, "Guest");
            require(texture(service, coldGuest, true) == null, "Unknown aliases must keep the native result");
            long previous = Files.getLastModifiedTime(identities).toMillis();
            // The peer announcement can arrive after a client starts resolving profiles.
            Files.write(identities, Arrays.asList(
                identity("Host", HOST, HOST_TUNNEL), identity("Guest", GUEST, GUEST_TUNNEL),
                identity("AmbiguousA", HOST, CONFLICTED_TUNNEL),
                identity("AmbiguousB", GUEST, CONFLICTED_TUNNEL)), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(identities, FileTime.fromMillis(previous + 2000));

            assertSkin(service, HOST, "Host", hostUrl, null, hostSkin);
            assertSkin(service, GUEST, "Guest", guestUrl, "slim", guestSkin);
            if (baseline) {
                assertNativeSignature(service, HOST_TUNNEL, "Different Host game name", MOJANG);
                assertNativeSignature(service, GUEST_TUNNEL, "Different Guest game name", MOJANG);
                System.out.println("PASS published baseline resolves canonical profiles and reproduces both tunnel skin misses");
                return;
            }
            assertSkin(service, HOST_TUNNEL, "Different Host game name", hostUrl, null, hostSkin);
            assertSkin(service, GUEST_TUNNEL, "Different Guest game name", guestUrl, "slim", guestSkin);
            require(guestUrl.equals(texture(service, coldGuest, true).getUrl()), "An already constructed guest profile missed the updated registry");

            assertNativeSignature(service, CONFLICTED_TUNNEL, "Guest", MOJANG);
            UUID nameOnly = UUID.nameUUIDFromBytes("OfflinePlayer:Guest".getBytes(StandardCharsets.UTF_8));
            assertNativeSignature(service, nameOnly, "Guest", MOJANG);
            String unregistered = guestUrl + "/unexpected";
            GameProfile outsideRegistry = new GameProfile(uuid(99), "Unknown");
            outsideRegistry.getProperties().put("textures", new Property("textures", payload(unregistered)));
            require(texture(service, outsideRegistry, false) == null, "An arbitrary localhost URL was accepted");
            assertSignedVanilla(service);
            System.out.println("PASS canonical host and guest, both tunnel aliases, verified loopback PNG bytes, slim model, registry reload, unknown and conflicting UUIDs, name-only refusal, unregistered URL refusal, native signature checks");
        } finally {
            endpoint.stop(0);
        }
    }

    private static void assertSkin(MinecraftSessionService service, UUID id, String name, String url,
            String model, byte[] expectedBytes) throws Exception {
        GameProfile profile = new GameProfile(id, name);
        profile.getProperties().put("textures", new Property("textures", payload(MOJANG)));
        profile.getProperties().put("untouched", new Property("untouched", "value"));
        MinecraftProfileTexture actual = texture(service, profile, true);
        require(actual != null && url.equals(actual.getUrl()), "Wrong texture for " + id);
        require(model == null ? actual.getMetadata("model") == null : model.equals(actual.getMetadata("model")), "Wrong model for " + id);
        require(profile.getId().equals(id) && profile.getName().equals(name), "Skin lookup changed a game profile");
        require(profile.getProperties().get("textures").size() == 1 && profile.getProperties().containsKey("untouched"), "Texture replacement touched other properties");
        URLConnection connection = new URL(actual.getUrl()).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream downloaded = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) != -1;) downloaded.write(buffer, 0, n);
            require(Arrays.equals(expectedBytes, downloaded.toByteArray()), "Wrong loopback PNG bytes for " + id);
        }
    }

    private static void assertNativeSignature(MinecraftSessionService service, UUID id, String name, String url) {
        GameProfile profile = new GameProfile(id, name);
        profile.getProperties().put("textures", new Property("textures", payload(url)));
        try {
            texture(service, profile, true);
            throw new IllegalStateException("Unregistered UUID " + id + " bypassed signature enforcement");
        } catch (InsecureTextureException expected) {
            require(expected.getMessage().contains("Signature is missing"), "Unexpected Authlib reason");
        }
    }

    private static void assertSignedVanilla(MinecraftSessionService service) throws Exception {
        GameProfile profile = new GameProfile(uuid(100), "Vanilla");
        String payload = payload(MOJANG);
        // Use a fixture key only in this process. The real verifier still decides whether to accept the texture.
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(1024);
        KeyPair keyPair = keys.generateKeyPair();
        Field publicKey = service.getClass().getDeclaredField("publicKey");
        publicKey.setAccessible(true);
        publicKey.set(service, keyPair.getPublic());
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        Property signed = new Property("textures", payload, Base64.getEncoder().encodeToString(signer.sign()));
        profile.getProperties().put("textures", signed);
        require(MOJANG.equals(texture(service, profile, true).getUrl()), "Valid native signature was not retained");
        require(profile.getProperties().get("textures").iterator().next() == signed, "Signed property was replaced");
    }

    private static MinecraftProfileTexture texture(MinecraftSessionService service, GameProfile profile, boolean secure) {
        return service.getTextures(profile, secure).get(MinecraftProfileTexture.Type.SKIN);
    }

    private static byte[] png(int color) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        require(ImageIO.write(image, "png", output), "PNG encoder unavailable");
        return output.toByteArray();
    }

    private static String register(HttpServer endpoint, UUID id, byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value & 0xff));
        String path = "/skin/" + id + "/" + hash;
        endpoint.createContext(path, exchange -> {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
        });
        return "http://127.0.0.1:" + endpoint.getAddress().getPort() + path;
    }

    private static String row(UUID id, String url, String model) {
        return id + "|" + url.substring(url.lastIndexOf('/') + 1) + "|" + model + "|" + url;
    }

    private static String identity(String name, UUID canonical, UUID tunnel) {
        return name + "|" + canonical + "|" + tunnel;
    }

    private static String payload(String url) {
        return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(StandardCharsets.UTF_8));
    }

    private static UUID uuid(int value) { return new UUID(0, value); }

    private static void require(boolean passed, String message) {
        if (!passed) throw new IllegalStateException(message);
    }
}
