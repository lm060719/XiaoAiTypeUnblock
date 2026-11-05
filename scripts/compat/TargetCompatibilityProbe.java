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

    public static void main(String[] args) {
        try { run(args); } catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
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
            Class<?> item = load(target, "z9.g6");
            Class<?> callback = load(target, "bd.b");
            method(callback, "g", Object.class, Object.class);
            method(load(target, "z9.u7"), "a0", void.class, String.class, callback,
                    load(target, "kotlin.jvm.functions.Function0"), java.util.Set.class,
                    load(target, "s0.p"), int.class);
            method(load(target, "oc.m"), "T0", java.util.ArrayList.class, Iterable.class, int.class);
            method(load(target, "z9.c3"), "m", Object.class, Object.class, Object.class);
            check(load(target, "gb.u0").getDeclaredField("b").getType()
                    == load(target, "com.mi.ime.MiInputMethodService"), "picker callback service");
            Object[] modes = new Object[4];
            String[] ids = {"qwerty_chinese", "t9_chinese", "handwriting_chinese", "stroke_chinese"};
            for (int index = 0; index < ids.length; index++) {
                modes[index] = item.getConstructor(int.class, String.class, String.class)
                        .newInstance(26, ids[index], ids[index]);
            }
            Class<?> picker = Class.forName("io.mo.xatype.keyboard.KeyboardPickerBridge");
            Class<?> abiType=Class.forName("io.mo.xatype.compat.KeyboardAbi");
            Object abiCompanion=abiType.getField("Companion").get(null);
            Method reference=null;
            for(Method candidate:abiCompanion.getClass().getMethods()) if(candidate.getName().startsWith("reference")) reference=candidate;
            check(reference!=null,"explicit reference profile injection");
            Object abi=reference.invoke(abiCompanion,target);
            Object bridge = picker.getConstructor(ClassLoader.class,abiType).newInstance(target,abi);
            Method extend = picker.getMethod("extend", Object[].class, boolean.class, boolean.class);
            java.util.List<?> choices = (java.util.List<?>) extend.invoke(bridge, modes, true, true);
            check(choices.size() == 6, "two independent keyboard choices");
            check(item.getDeclaredField("a").get(choices.get(4)).equals("xatype_shape"), "shape picker id");
            check(item.getDeclaredField("a").get(choices.get(5)).equals("xatype_keyboard18"), "18-key picker id");
            check(item.getDeclaredField("b").get(choices.get(4)).equals("\u864e\u7801/\u4e94\u7b14"), "shape picker label");
            check(((java.util.List<?>) extend.invoke(bridge, modes, false, false)).size() == 4,
                    "both visibility switches off preserve native picker");
            Class<?> preference = load(target, "androidx.preference.Preference");
            method(preference, "D", void.class, String.class);
            method(preference, "G", void.class, String.class);
            method(load(target, "androidx.preference.PreferenceGroup"), "L", boolean.class, preference);
            check(load(target, "miuix.preference.SingleChoicePreference").getConstructor(Context.class) != null,
                    "native settings row constructor");
            method(load(target, "qh.c"), "y", void.class, load(target, "dg.u"), String.class,
                    java.util.Map.class, String.class, callback);
            Class<?> rect = load(target, "z9.c");
            Object savedTallRect = rect.getDeclaredConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(0, -250, 50, 50);
            for (String field : new String[]{"a", "b", "c", "d", "f"}) {
                check(rect.getDeclaredField(field).getType() == int.class, "adjust rect " + field);
            }
            check(rect.getDeclaredField("f").getInt(savedTallRect) == 300,
                    "saved rectangle retains height above native cap");
            method(load(target, "ab.z1"), "l", rect);
            method(load(target, "gb.m0"), "a", void.class, int.class,
                    load(target, "com.mi.ime.MiInputMethodService"), load(target, "s0.p"));
            Class<?> topDrag = load(target, "z9.e6");
            method(topDrag, "m", Object.class, Object.class, Object.class);
            check(topDrag.getDeclaredField("a").getType() == int.class, "drag branch discriminator");
            method(load(target, "ed.a"), "k", int.class, int.class, int.class, int.class);
            // The panel animation and UI-state members are fingerprinted (HostSymbols
            // PANEL_* / UI_*); verify them with verify-host-fingerprints.ps1.
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
