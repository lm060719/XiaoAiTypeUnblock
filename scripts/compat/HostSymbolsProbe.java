import dalvik.system.DexClassLoader;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Runs the module's fingerprint scan against real target APKs on Android,
 * without starting or modifying the IME. Usage:
 * HostSymbolsProbe <HostSymbols|PhraseSymbols> <libDir> <workDir> <target.apk>...
 */
public final class HostSymbolsProbe {
    public static void main(String[] args) {
        try { run(args); } catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        // libdexkit links against the shared C++ runtime shipped beside it.
        for (String name : new String[]{"libc++_shared.so", "libdexkit.so"}) {
            File lib = new File(args[1], name);
            if (lib.isFile()) System.load(lib.getAbsolutePath());
        }
        Class<?> symbols = Class.forName("io.mo.xatype.compat." + args[0]);
        Object instance = symbols.getField("INSTANCE").get(null);
        Method init = symbols.getMethod("init", ClassLoader.class, String.class, File.class,
                String.class, Class.forName("kotlin.jvm.functions.Function0"));
        Method describe = symbols.getMethod("describe");
        Method has = symbols.getMethod("has", String.class);
        Object noNative = Proxy.newProxyInstance(symbols.getClassLoader(),
                new Class<?>[]{Class.forName("kotlin.jvm.functions.Function0")},
                (proxy, method, methodArgs) -> null);
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        boolean failed = false;
        Set<String> required = new HashSet<>();
        for (String arg : args) if (arg.startsWith("--require=")) required.addAll(Arrays.asList(arg.substring(10).split(",")));

        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("--require=")) continue;
            String apk = args[i];
            File work = new File(args[2], "t" + i);
            work.mkdirs();
            File cache = new File(work, "symbols.json");
            cache.delete();
            ClassLoader target = new DexClassLoader(apk, work.getAbsolutePath(), null, boot);
            System.out.println("== " + new File(apk).getName());
            System.out.println("scan:  " + init.invoke(instance, target, apk, cache, "probe", noNative));
            System.out.println("cache: " + init.invoke(instance, target, apk, cache, "probe", noNative));
            if (!symbols.getMethod("getSource").invoke(instance).toString().equals("CACHE")) {
                failed = true; System.out.println("  CACHE RELOAD FAILED");
            }
            for (String key : required) if (!(boolean) has.invoke(instance, key)) {
                failed = true; System.out.println("  REQUIRED KEY MISSING " + key);
            }
            @SuppressWarnings("unchecked")
            Map<String, List<String>> resolved = (Map<String, List<String>>) describe.invoke(instance);
            if (resolved.isEmpty()) failed = true;
            for (Map.Entry<String, List<String>> entry : resolved.entrySet()) {
                System.out.println("  " + entry.getKey() + " = " +
                    (!entry.getValue().isEmpty() && entry.getValue().get(0).contains("=") ?
                        entry.getValue().size() + " entries" : entry.getValue()));
            }
            if (args[0].equals("HostSymbols")) verifyKeyboard(symbols, instance, target);
            else {
                Class<?> locator=Class.forName("io.mo.xatype.compat.LoadedApkSource");
                Object path=locator.getMethod("find",ClassLoader.class,String.class).invoke(locator.getField("INSTANCE").get(null),
                        target,"com.miui.inputmethod.MiuiClipboardManager");
                if(!apk.equals(path)) throw new AssertionError("Dynamic clipboard APK source: "+path);
                System.out.println("  DYNAMIC APK SOURCE OK");
            }
            // Every cached descriptor must load back through reflection.
            for (String key : resolved.keySet()) {
                boolean ok = (boolean) has.invoke(instance, key) && load(symbols, instance, key);
                if (!ok) {
                    System.out.println("  RELOAD FAILED " + key);
                    failed = true;
                }
            }
        }
        System.out.println(failed ? "FAIL" : "DONE");
    }

    /** A key holds methods, one class, or one field; any of them must load. */
    private static boolean load(Class<?> symbols, Object instance, String key) throws Exception {
        List<?> raw = (List<?>) symbols.getMethod("strings", String.class).invoke(instance, key);
        // Entries such as "palette.isDark=Y" are data rather than descriptors.
        if (raw != null && !raw.isEmpty() && String.valueOf(raw.get(0)).contains("=")) return true;
        if (key.equals("manager.runnables") && raw != null && raw.isEmpty()) return true;
        List<?> methods = (List<?>) symbols.getMethod("methods", String.class).invoke(instance, key);
        return !methods.isEmpty() ||
                symbols.getMethod("clazz", String.class).invoke(instance, key) != null ||
                symbols.getMethod("field", String.class).invoke(instance, key) != null;
    }

    private static void verifyKeyboard(Class<?> symbols, Object instance, ClassLoader target) throws Exception {
        Method has = symbols.getMethod("has", String.class);
        Class<?> abiType = Class.forName("io.mo.xatype.compat.KeyboardAbi");
        Object companion = abiType.getField("Companion").get(null);
        Method require = companion.getClass().getMethod("require", ClassLoader.class, String[].class);
        Object abi = require.invoke(companion, target, new String[0]);
        String[][] bridges = {
            {"keyboard.engine", "io.mo.xatype.shape.ShapeHostBridge"},
            {"keyboard.engine", "io.mo.xatype.keyboard18.Keyboard18HostBridge"},
            {"keyboard.engine", "io.mo.xatype.keyboard.EmojiHostBridge"},
            {"keyboard.layout", "io.mo.xatype.keyboard18.Keyboard18HostLayout"},
            {"keyboard.layout", "io.mo.xatype.keyboard.KeyboardShortcutHostLayout"},
            {"keyboard.actions", "io.mo.xatype.keyboard.KeyboardShortcutHostActions"},
            {"keyboard.picker", "io.mo.xatype.keyboard.KeyboardPickerBridge"},
            {"keyboard.swipe", "io.mo.xatype.keyboard.ShortcutSwipeHostBridge"}
        };
        for (String[] bridge : bridges) if ((boolean) has.invoke(instance, bridge[0])) {
            Class.forName(bridge[1]).getConstructor(ClassLoader.class, abiType).newInstance(target, abi);
            System.out.println("  BINDINGS OK " + bridge[1]);
        }
        if ((boolean) has.invoke(instance, "keyboard.surfaceText")) {
            List<?> methods = (List<?>) abiType.getMethod("namedMethods", String.class, String.class)
                    .invoke(abi, "qh.c", "h");
            Object owner = abiType.getMethod("type", String.class).invoke(abi, "qh.c");
            if (methods.size() != 1 || ((Method) methods.get(0)).getDeclaringClass() != owner)
                throw new IllegalStateException("Clipboard text scope did not bind uniquely");
            System.out.println("  BINDINGS OK keyboard.surfaceText");
        }
    }
}
