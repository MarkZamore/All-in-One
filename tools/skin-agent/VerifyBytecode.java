import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import minecraft.portable.identity.LegacySkinAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Uses the real packaged agent and exact installed client and Simple RPC class bytes. */
public final class VerifyBytecode {
    private static final String MENU = "net/minecraft/client/gui/screen/MainMenuScreen";
    private static final String RPC = "me/hypherionmc/simplerpc/Utils";
    private static final String HOOKS = "minecraft.portable.identity.LegacyMultiplayerHooks";

    public static void main(String[] args) throws Exception {
        byte[] menu = entry(Paths.get(args[0]), MENU + ".class");
        byte[] rpc = entry(Paths.get(args[1]), RPC + ".class");
        byte[] bootstrapHooks = entry(Paths.get(args[2]), "skin-hooks.jar");
        LegacySkinAgent agent = new LegacySkinAgent();
        System.clearProperty("link.e4steam.launcherManagedLan");
        require(agent.transform(null, MENU, null, null, menu) == null, "Standalone menu changed");
        require(agent.transform(null, RPC, null, null, rpc) == null, "Standalone Simple RPC changed");
        System.setProperty("link.e4steam.launcherManagedLan", "true");
        byte[] changedMenu = agent.transform(null, MENU, null, null, menu);
        byte[] changedRpc = agent.transform(null, RPC, null, null, rpc);
        require(changedMenu != null && changedRpc != null, "Pinned classes were not transformed");
        verifyMenu(changedMenu);
        verifyRpc(changedRpc);
        verifyHooks(bootstrapHooks);
        System.out.println("PASS exact native bytecode and Java 8 multiplayer hook behavior");
    }

    private static void verifyMenu(byte[] bytes) {
        int[] targets = new int[3];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                if (!method.equals("func_73969_a") || !descriptor.equals("(II)V")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private Object label;

                    @Override public void visitLdcInsn(Object value) {
                        label = value;
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                        if (!owner.equals(HOOKS.replace('.', '/'))) return;
                        require(method.equals("enableMultiplayerMenu"), "Unexpected menu policy");
                        require(label.equals("menu.multiplayer"), "Realms or another menu item was changed");
                        targets[0]++;
                    }

                    @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        if (opcode != Opcodes.PUTFIELD || !field.equals("field_230693_o_")) return;
                        if ("menu.multiplayer".equals(label)) targets[1]++;
                        if ("menu.online".equals(label)) targets[2]++;
                    }
                };
            }
        }, 0);
        require(targets[0] == 1 && targets[1] == 1 && targets[2] == 1, "Unexpected count of native menu writes");
    }

    private static void verifyRpc(byte[] bytes) {
        int[] calls = new int[2];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                if (!method.equals("parseVars") || !descriptor.equals("(Ljava/lang/String;)Ljava/lang/String;")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                        require(!method.equals("func_147224_a"), "An unguarded Simple RPC ping remains");
                        if (owner.equals(HOOKS.replace('.', '/'))) {
                            if (method.equals("pingForMaximum")) calls[0]++;
                            if (method.equals("populationText")) calls[1]++;
                        }
                    }
                };
            }
        }, 0);
        require(calls[0] == 1 && calls[1] == 1, "Wrong number of Simple RPC rewrites");
    }

    private static void verifyHooks(byte[] hooksJar) throws Exception {
        FixtureLoader loader = new FixtureLoader();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(hooksJar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals(HOOKS.replace('.', '/') + ".class")) {
                    loader.add(HOOKS, read(zip));
                    break;
                }
            }
        }
        String dataClass = "net.minecraft.client.multiplayer.ServerData";
        String pingClass = "net.minecraft.client.network.ServerPinger";
        String runtimeClass = "link.e4steam.steam.SteamRuntime";
        String sessionClass = runtimeClass + "$View";
        String componentClass = "net.minecraft.util.text.ITextComponent";
        String textClass = "test.PopulationText";
        String minecraftClass = "test.Client";

        ClassWriter data = regular(dataClass, null);
        data.visitField(Opcodes.ACC_PUBLIC, "field_78845_b", "Ljava/lang/String;", null, null).visitEnd();
        loader.add(dataClass, data.toByteArray());
        ClassWriter pinger = regular(pingClass, null);
        pinger.visitField(Opcodes.ACC_PUBLIC, "calls", "I", null, null).visitEnd();
        MethodVisitor ping = pinger.visitMethod(Opcodes.ACC_PUBLIC, "func_147224_a",
            "(L" + dataClass.replace('.', '/') + ";Ljava/lang/Runnable;)V", null, null);
        ping.visitCode();
        ping.visitVarInsn(Opcodes.ALOAD, 0);
        ping.visitInsn(Opcodes.DUP);
        ping.visitFieldInsn(Opcodes.GETFIELD, pingClass.replace('.', '/'), "calls", "I");
        ping.visitInsn(Opcodes.ICONST_1);
        ping.visitInsn(Opcodes.IADD);
        ping.visitFieldInsn(Opcodes.PUTFIELD, pingClass.replace('.', '/'), "calls", "I");
        ping.visitInsn(Opcodes.RETURN);
        ping.visitMaxs(0, 0);
        ping.visitEnd();
        loader.add(pingClass, pinger.toByteArray());

        ClassWriter client = regular(minecraftClass, null);
        client.visitField(Opcodes.ACC_PRIVATE, "field_238175_ae_", "Z", null, null).visitEnd();
        loader.add(minecraftClass, client.toByteArray());

        ClassWriter component = new ClassWriter(0);
        component.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
            componentClass.replace('.', '/'), null, "java/lang/Object", null);
        component.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getString", "()Ljava/lang/String;", null, null).visitEnd();
        loader.add(componentClass, component.toByteArray());
        ClassWriter text = regular(textClass, componentClass);
        constantMethod(text, "getString", "()Ljava/lang/String;", "2/20", false);
        loader.add(textClass, text.toByteArray());

        ClassWriter runtime = regular(runtimeClass, null);
        objectMethod(runtime, "get", runtimeClass, true);
        objectMethod(runtime, "safeSessionView", sessionClass, false);
        loader.add(runtimeClass, runtime.toByteArray());
        ClassWriter view = regular(sessionClass, null);
        view.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "currentRole", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor role = view.visitMethod(Opcodes.ACC_PUBLIC, "roleCode", "()Ljava/lang/String;", null, null);
        role.visitCode();
        role.visitFieldInsn(Opcodes.GETSTATIC, sessionClass.replace('.', '/'), "currentRole", "Ljava/lang/String;");
        role.visitInsn(Opcodes.ARETURN);
        role.visitMaxs(0, 0);
        role.visitEnd();
        constantMethod(view, "active", "()Z", Boolean.TRUE, false);
        loader.add(sessionClass, view.toByteArray());

        Class<?> policy = loader.loadClass(HOOKS);
        Object server = loader.loadClass(dataClass).newInstance();
        Object serverPinger = loader.loadClass(pingClass).newInstance();
        Field endpoint = server.getClass().getField("field_78845_b");
        Field pingCount = serverPinger.getClass().getField("calls");
        Field sessionRole = loader.loadClass(sessionClass).getField("currentRole");
        Method pingMaximum = policy.getMethod("pingForMaximum", Object.class, Object.class, Runnable.class, String.class);
        endpoint.set(server, "example.invalid:25565");
        pingMaximum.invoke(null, serverPinger, server, null, "Players: %players%");
        require(pingCount.getInt(serverPinger) == 0, "The default presence opened an unneeded connection");
        pingMaximum.invoke(null, serverPinger, server, null, "Max: %maxplayers%");
        require(pingCount.getInt(serverPinger) == 1, "Direct endpoint did not request the needed status");
        endpoint.set(server, "s-test-world.steam:25565");
        pingMaximum.invoke(null, serverPinger, server, null, "Max: %maxplayers%");
        require(pingCount.getInt(serverPinger) == 1, "A Steam address was pinged");
        endpoint.set(server, "127.0.0.1:25566");
        sessionRole.set(null, "GUEST");
        pingMaximum.invoke(null, serverPinger, server, null, "Max: %maxplayers%");
        sessionRole.set(null, "DEDICATED_SERVER_CLIENT");
        pingMaximum.invoke(null, serverPinger, server, null, "Max: %maxplayers%");
        require(pingCount.getInt(serverPinger) == 1, "A local Steam bridge was pinged");
        sessionRole.set(null, "INTEGRATED_HOST");
        pingMaximum.invoke(null, serverPinger, server, null, "Max: %maxplayers%");
        require(pingCount.getInt(serverPinger) == 2, "A direct local server was mistaken for Steam");
        Method population = policy.getMethod("populationText", Object.class);
        require(population.invoke(null, new Object[]{null}).equals("0/0"), "Missing status is not safe");
        require(population.invoke(null, loader.loadClass(textClass).newInstance()).equals("2/20"), "Cached population changed");

        Object minecraft = loader.loadClass(minecraftClass).newInstance();
        Method menu = policy.getMethod("enableMultiplayerMenu", boolean.class, Object.class);
        require((Boolean) menu.invoke(null, true, minecraft), "Vanilla decision was not retained");
        require(!(Boolean) menu.invoke(null, false, minecraft), "Explicit disableMultiplayer was ignored");
        Field localPermission = minecraft.getClass().getDeclaredField("field_238175_ae_");
        localPermission.setAccessible(true);
        localPermission.setBoolean(minecraft, true);
        require((Boolean) menu.invoke(null, false, minecraft), "Launcher session did not get the Multiplayer list");
    }

    private static ClassWriter regular(String type, String implemented) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, type.replace('.', '/'), null, "java/lang/Object",
            implemented == null ? null : new String[]{implemented.replace('.', '/')});
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    private static void constantMethod(ClassWriter type, String method, String descriptor, Object value, boolean isStatic) {
        MethodVisitor out = type.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), method, descriptor, null, null);
        out.visitCode();
        if (value instanceof Boolean) {
            out.visitInsn((Boolean) value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            out.visitInsn(Opcodes.IRETURN);
        } else {
            out.visitLdcInsn(value);
            out.visitInsn(Opcodes.ARETURN);
        }
        out.visitMaxs(0, 0);
        out.visitEnd();
    }

    private static void objectMethod(ClassWriter type, String method, String returned, boolean isStatic) {
        String target = returned.replace('.', '/');
        MethodVisitor out = type.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), method, "()L" + target + ";", null, null);
        out.visitCode();
        out.visitTypeInsn(Opcodes.NEW, target);
        out.visitInsn(Opcodes.DUP);
        out.visitMethodInsn(Opcodes.INVOKESPECIAL, target, "<init>", "()V", false);
        out.visitInsn(Opcodes.ARETURN);
        out.visitMaxs(0, 0);
        out.visitEnd();
    }

    private static byte[] entry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return read(in);
        }
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = input.read(chunk)) != -1;) bytes.write(chunk, 0, n);
        return bytes.toByteArray();
    }

    private static void require(boolean actual, String message) {
        if (!actual) throw new IllegalStateException(message);
    }

    private static class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        void add(String name, byte[] bytes) {
            classes.put(name, bytes);
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
