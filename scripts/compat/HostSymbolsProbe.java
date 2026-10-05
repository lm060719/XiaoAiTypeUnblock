import dalvik.system.DexClassLoader;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * Runs the module's fingerprint scan against real target APKs on Android,
 * without starting or modifying the IME. Usage:
 * HostSymbolsProbe <HostSymbols|PhraseSymbols> <libDir> <workDir> <target.apk>...
 */
public final class HostSymbolsProbe {
    public static void main(String[] args) throws Exception {
        // libdexkit links against the shared C++ runtime shipped beside it.
        for (String name : new String[]{"libc++_shared.so", "libdexkit.so"}) {
            File lib = new File(args[1], name);
            if (lib.isFile()) System.load(lib.getAbsolutePath());
        }
        Class<?> symbols = Class.forName("io.mo.xatype.compat." + args[0]);
        Object instance = symbols.getField("INSTANCE").get(null);
        Method init = symbols.getMethod("init", ClassLoader.class, String.class, File.class,
                long.class, Class.forName("kotlin.jvm.functions.Function0"));
        Method describe = symbols.getMethod("describe");
        Method has = symbols.getMethod("has", String.class);
        Object noNative = Proxy.newProxyInstance(symbols.getClassLoader(),
                new Class<?>[]{Class.forName("kotlin.jvm.functions.Function0")},
                (proxy, method, methodArgs) -> null);
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        boolean failed = false;

        for (int i = 3; i < args.length; i++) {
            String apk = args[i];
            File work = new File(args[2], "t" + i);
            work.mkdirs();
            File cache = new File(work, "symbols.json");
            cache.delete();
            ClassLoader target = new DexClassLoader(apk, work.getAbsolutePath(), null, boot);
            System.out.println("== " + new File(apk).getName());
            System.out.println("scan:  " + init.invoke(instance, target, apk, cache, 1L, noNative));
            System.out.println("cache: " + init.invoke(instance, target, apk, cache, 1L, noNative));
            @SuppressWarnings("unchecked")
            Map<String, List<String>> resolved = (Map<String, List<String>>) describe.invoke(instance);
            for (Map.Entry<String, List<String>> entry : resolved.entrySet()) {
                System.out.println("  " + entry.getKey() + " = " + entry.getValue());
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
        List<?> methods = (List<?>) symbols.getMethod("methods", String.class).invoke(instance, key);
        return !methods.isEmpty() ||
                symbols.getMethod("clazz", String.class).invoke(instance, key) != null ||
                symbols.getMethod("field", String.class).invoke(instance, key) != null;
    }
}
