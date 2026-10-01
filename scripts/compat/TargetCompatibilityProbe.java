import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;

/** Runs against real APK DEX on Android, without starting or modifying the IME. */
public final class TargetCompatibilityProbe {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static String property(Object profile, String name) throws Exception {
        return (String) profile.getClass().getMethod("get" + name).invoke(profile);
    }

    private static Class<?> load(ClassLoader loader, String name) throws Exception {
        return Class.forName(name, false, loader);
    }

    private static void method(Class<?> type, String name, Class<?> result, Class<?>... args)
            throws Exception {
        check(type.getDeclaredMethod(name, args).getReturnType() == result,
                type.getName() + "." + name + " return type");
    }

    public static void main(String[] args) throws Exception {
        Class<?> compat = Class.forName("io.mo.xatype.compat.TargetCompatibility");
        Object singleton = compat.getField("INSTANCE").get(null);
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        Object unknown = compat.getMethod("detect", ClassLoader.class).invoke(singleton, boot);
        check(unknown.toString().equals("UNKNOWN"), "boot loader must be UNKNOWN");
        check(compat.getMethod("modernKeyboardProfile", ClassLoader.class)
                .invoke(singleton, boot) == null, "unknown loader must have no appearance profile");

        ClassLoader target = new DexClassLoader(args[0], args[1], null, boot);
        Method detect = compat.getMethod("detect", ClassLoader.class);
        Object generation = detect.invoke(singleton, target);
        check(generation.toString().equals(args[2]), "expected " + args[2] + ", got " + generation);
        check(detect.invoke(singleton, target) == generation, "cached generation must agree");
        Object profile = compat.getMethod("modernKeyboardProfile", ClassLoader.class)
                .invoke(singleton, target);
        if (profile != null) {
            Class<?> service = load(target, "com.mi.ime.MiInputMethodService");
            Class<?> helper = load(target, property(profile, "HelperClassName"));
            check(service.getDeclaredField("hyperMaterialHelper").getType() == helper, "helper field");
            check(helper.getDeclaredField("a").getType() == service, "helper service field");
            check(helper.getDeclaredField("i").getType() == View.class, "material field");
            check(helper.getDeclaredField("e").getType().getMethod("setValue", Object.class)
                    .getReturnType() == void.class, "material state setter");
            method(helper, "b", boolean.class, View.class);
            method(helper, "f", boolean.class, boolean.class, FrameLayout.class, int.class);
            method(helper, "e", void.class);
            method(helper, property(profile, "MaterialSupportMethod"), boolean.class);
            method(helper, property(profile, "MaterialUpdateMethod"), void.class);
            method(helper, property(profile, "MaterialRefreshMethod"), void.class);
            method(helper, property(profile, "MaterialCleanupMethod"), void.class);
            method(helper, property(profile, "MaterialVisibilityMethod"), void.class, boolean.class);
            Class<?> renderer = load(target, property(profile, "RendererClassName"));
            method(renderer, property(profile, "RendererUpdateMethod"), void.class);
            check(renderer.getDeclaredField("a").getType() == service, "renderer service field");
            check(renderer.getDeclaredField("c").getType() == View.class, "renderer view field");
            renderer.getDeclaredField("d");
            Class<?> palette = load(target, property(profile, "PaletteClassName"));
            for (String field : new String[]{"a", "b", "c", "d", "e", "f", "g", "h", "i",
                    "j", "k", "l", "m", "u", "w", "x", "y", "A", "I", "L", "M"}) {
                check(palette.getDeclaredField(field).getType() == long.class, "palette " + field);
            }
            Class<?> apps = palette.getDeclaredField("U").getType();
            for (String field : new String[]{"a", "b", "c", "d", "f", "g", "h", "i"}) {
                check(apps.getDeclaredField(field).getType() == long.class, "apps panel " + field);
            }
            check(palette.getDeclaredField("Y").getType() == boolean.class, "palette dark flag");
            Class<?> holder = load(target, property(profile, "PaletteHolderClassName"));
            check(holder.getDeclaredField("d").getType() == palette, "light palette");
            check(holder.getDeclaredField("e").getType() == palette, "dark palette");
            Class<?> factory = load(target, property(profile, "PaletteFactoryClassName"));
            method(factory, "z", palette, load(target, "s0.p"));
            for (String field : new String[]{"r", "s"}) {
                check(helper.getDeclaredField(field).getType().getMethod("getValue")
                        .getReturnType() == Object.class, "material token " + field);
            }
        }
        String parser = (String) compat.getMethod("aiSafetyParserClassName", ClassLoader.class)
                .invoke(singleton, target);
        if (generation.toString().equals("V21053")) {
            Class<?> expansion = load(target, "gb.p");
            Class<?> animatable = load(target, "w.b");
            Class<?> continuation = load(target, "rc.c");
            Class<?> spec = load(target, "w.h");
            Class<?> spring = load(target, "w.n0");
            check(expansion.getDeclaredField("e").getType() == int.class, "expansion resume label");
            check(expansion.getDeclaredField("h").getType() == animatable, "expansion animatable");
            check(spec.isAssignableFrom(spring), "spring implements animation spec");
            method(animatable, "e", Object.class, Object.class, continuation);
            method(animatable, "c", Object.class, animatable, Object.class, spec,
                    load(target, "bd.b"), continuation, int.class);
            method(load(target, "w.c"), "p", spring, float.class, float.class, Object.class, int.class);
            Class<?> service = load(target, "com.mi.ime.MiInputMethodService");
            Class<?> manager = load(target, "ab.z1");
            Class<?> mode = load(target, "y7.l");
            method(service, "getUiStateManager$app_iflytekFullRelease", manager);
            method(manager, "n", mode);
            method(manager, "A", boolean.class);
            method(manager, "v", boolean.class);
            check(manager.getDeclaredField("n").getType().getMethod("setValue", Object.class)
                    .getReturnType() == void.class, "AI closing state setter");
            check(mode.getDeclaredField("k").getType() == mode, "AI input mode");
            method(load(target, "lb.c"), "d0", void.class, service);
            Class<?> completion = load(target, "gb.n");
            check(completion.getDeclaredField("e").getType() == int.class, "completion variant");
            check(completion.getDeclaredField("f").getType() == service, "completion service");
            check(completion.getDeclaredField("g").getType() == boolean.class, "completion threshold flag");
            method(completion, "r", Object.class, Object.class);
        }
        for (String name : new String[]{"h", "e", "f"}) {
            load(target, parser).getDeclaredMethod(name, String.class);
            checks++;
        }
        String voice = (String) compat.getMethod("voiceModerationClassName", ClassLoader.class)
                .invoke(singleton, target);
        String voiceMethod = (String) compat.getMethod("voiceModerationMethodName", ClassLoader.class)
                .invoke(singleton, target);
        method(load(target, voice), voiceMethod, void.class, Context.class, String.class, String.class);
        String asr = (String) compat.getMethod("asrManagerClassName", ClassLoader.class)
                .invoke(singleton, target);
        load(target, asr).getDeclaredMethod("m", int.class, String.class);
        checks++;
        String callback = (String) compat.getMethod("asrCallbackClassName", ClassLoader.class)
                .invoke(singleton, target);
        method(load(target, callback), "e", void.class, Bundle.class);
        method(load(target, "com.iflytek.inputmethod.smartengine.c1"), "onPyCloudAttachUpdate",
                void.class, int.class, String.class, String.class, String.class, String.class);
        Class<?> result = load(target, "com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult");
        method(result, "getBlackListStr", String.class);
        method(result, "setBlackListStr", void.class, String.class);
        method(result, "fromBundle", void.class, Bundle.class);
        check(result.getDeclaredField("b").getType() == String.class, "blacklist backing field");
        System.out.println("PASS profile=" + generation + " checks=" + checks);
    }
}
