package io.mo.xatype;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;

/** Explicitly invoked, real-device-only regression harness; no third-party runner required. */
public class Issue9DeviceInstrumentation extends Instrumentation {
    private Bundle arguments;
    private volatile Activity activity;
    private EditText input;
    private File directory;
    private UiAutomation automation;
    private SharedPreferences prefs;
    private final Map<String,Object> saved = new HashMap<>();
    private final JSONArray results = new JSONArray();
    private final List<KeyEvent> received = Collections.synchronizedList(new ArrayList<>());
    private int failed;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void callActivityOnResume(Activity target) { super.callActivityOnResume(target); activity = target; }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            status("Starting physical-device QA");
            String hardware = android.os.Build.HARDWARE;
            if (hardware.contains("goldfish") || hardware.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"))
                throw new IllegalStateException("A physical device is required");
            directory = new File(getTargetContext().getFilesDir(), "issue9-device-qa");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("QA directory");
            automation = getUiAutomation();
            status("UI automation connected");
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            prefs = getTargetContext().getSharedPreferences("settings",Context.MODE_PRIVATE);
            configure("pref_splash_animation",false);
            status("Launching module test editor");
            shell("am start -W -n io.mo.xatype/.ui.MainActivity");
            for (int i=0;i<100 && activity == null;i++) SystemClock.sleep(100);
            if (activity == null) throw new IllegalStateException("Module activity did not resume");
            status("Module activity started");
            SystemClock.sleep(2200);
            runOnMainSync(() -> input = activity.findViewById(io.mo.xatype.R.id.keyboardTestInput));
            // Skip an active splash through its normal touch handling, if still present.
            Rect field = bounds(input);
            status("Input bounds: " + field);
            tap(field.centerX(), field.centerY());
            SystemClock.sleep(700);
            runOnMainSync(() -> {
                input.requestFocus();
                ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            });
            SystemClock.sleep(2200);
            snapshot(arguments.getString("name", "probe"));
            status("Captured real-device editor and keyboard");
            if ("suite".equals(arguments.getString("case"))) suite();
            if ("insets".equals(arguments.getString("case"))) fixedNavigation();
            if ("onehand".equals(arguments.getString("case"))) oneHandSwitches();
            result.putString("result", failed == 0 ? "PASS" : "FAIL");
            result.putInt("failed", failed);
            if (failed > 0) resultCode = Activity.RESULT_CANCELED;
        } catch (Throwable failure) {
            result.putString("result", "FAIL: " + failure);
            result.putString("stack", android.util.Log.getStackTraceString(failure));
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            try {
                if (prefs != null) {
                    SharedPreferences.Editor edit = prefs.edit();
                    for (Map.Entry<String,Object> entry : saved.entrySet()) {
                        Object value = entry.getValue(); String key = entry.getKey();
                        if (value == null) edit.remove(key);
                        else if (value instanceof Boolean) edit.putBoolean(key,(Boolean)value);
                        else if (value instanceof Integer) edit.putInt(key,(Integer)value);
                        else if (value instanceof String) edit.putString(key,(String)value);
                    }
                    edit.commit();
                }
                if (directory != null) write("results.json",results.toString(2));
                if (automation != null && ("suite".equals(arguments.getString("case")) ||
                    "onehand".equals(arguments.getString("case")))) restartKeyboard();
            } catch (Throwable restoreFailure) {
                result.putString("restoreFailure",restoreFailure.toString()); resultCode=Activity.RESULT_CANCELED;
            }
        }
        finish(resultCode,result);
    }

    private void configure(String key,Object value) {
        if (!saved.containsKey(key)) saved.put(key,prefs.getAll().get(key));
        SharedPreferences.Editor edit=prefs.edit();
        if(value instanceof Boolean) edit.putBoolean(key,(Boolean)value);
        else if(value instanceof Integer) edit.putInt(key,(Integer)value);
        else edit.putString(key,(String)value);
        if(!edit.commit()) throw new IllegalStateException("Cannot save test configuration");
    }

    private void write(String name,String text) throws Exception {
        try(FileOutputStream stream=new FileOutputStream(new File(directory,name))) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private interface Checked { void run() throws Exception; }
    private void test(String name,Checked test) throws Exception {
        JSONObject entry=new JSONObject().put("name",name);
        try { test.run(); entry.put("result","PASS"); status("PASS " + name); }
        catch(Throwable failure) { failed++; entry.put("result","FAIL").put("error",failure.toString()); status("FAIL " + name + ": " + failure); }
        results.put(entry);write("results.json",results.toString(2));
    }
    private void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private void restartKeyboard() throws Exception {
        String previous=shell("pidof com.xiaomi.type").trim();
        shell("am force-stop --user 0 com.xiaomi.type");
        String after=shell("pidof com.xiaomi.type").trim();
        require(previous.isEmpty() || !previous.equals(after),"Host was not restarted: " + previous);
        shell("ime set com.xiaomi.type/com.mi.ime.MiInputMethodService");
        SystemClock.sleep(600);
        showKeyboard();
        status("Keyboard restarted " + previous + " -> " + shell("pidof com.xiaomi.type").trim());
    }
    private boolean imeVisible() {
        final boolean[] visible=new boolean[1];
        runOnMainSync(() ->visible[0]=input.getRootWindowInsets()!=null && input.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));
        return visible[0];
    }
    private void hideKeyboard() {
        runOnMainSync(() -> ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(input.getWindowToken(),0));
        for(int i=0;i<40 && imeVisible();i++) SystemClock.sleep(100);
        require(!imeVisible(),"Keyboard did not close");
        SystemClock.sleep(500);
    }
    private void fixedNavigation() throws Exception {
        hideKeyboard();
        final View nav=activity.findViewById(io.mo.xatype.R.id.navBar);
        final View root=activity.findViewById(io.mo.xatype.R.id.settingsRoot);
        final Rect baselineNav=bounds(nav), baselineRoot=bounds(root), baselineInput=bounds(input);
        snapshot("navigation-keyboard-closed");
        JSONArray measurements=new JSONArray();
        for(String tab:new String[]{"概览","键盘","外观"}) {
            test("fixed navigation with keyboard on " + tab,() -> {
                hideKeyboard();tapModule(tab);showKeyboard();
                Rect shownNav=bounds(nav), shownRoot=bounds(root), shownInput=bounds(input);
                final int[] state=new int[2];
                runOnMainSync(() -> {state[0]=nav.getVisibility();state[1]=nav.getPaddingBottom();});
                measurements.put(new JSONObject().put("tab",tab).put("beforeNav",baselineNav.toString())
                    .put("openNav",shownNav.toString()).put("beforeRoot",baselineRoot.toString())
                    .put("openRoot",shownRoot.toString()).put("visibility",state[0]).put("bottomPadding",state[1]));
                require(baselineNav.equals(shownNav),"Navigation moved: " + baselineNav + " -> " + shownNav);
                require(baselineRoot.equals(shownRoot),"Page resized: " + baselineRoot + " -> " + shownRoot);
                require(baselineInput.equals(shownInput),"Test input moved: " + baselineInput + " -> " + shownInput);
                require(state[0]==View.VISIBLE,"Navigation was hidden");
                snapshot("navigation-keyboard-open-" + measurements.length());
                hideKeyboard();
                require(baselineNav.equals(bounds(nav)),"Navigation did not keep its bounds after closing keyboard");
                tapModule("概览");
            });
        }
        write("navigation-insets.json",measurements.toString(2));
        snapshot("navigation-keyboard-restored");
    }
    private void showKeyboard() {
        runOnMainSync(() -> {
            input.requestFocus();
            InputMethodManager ime=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            ime.restartInput(input);ime.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
        });
        Rect field=bounds(input);tap(field.centerX(),field.centerY());
        for(int i=0;i<40 && (!imeVisible() || find("com.xiaomi.type","Q")==null);i++) {
            SystemClock.sleep(250);
            if(i%4==3) runOnMainSync(() ->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT));
        }
        SystemClock.sleep(600);
        require(imeVisible() && find("com.xiaomi.type","Q")!=null,"Keyboard did not become visible");
    }
    private AccessibilityNodeInfo find(String pkg,String caption) {
        // Compose reuses virtual node IDs during live layout changes. Discard old geometry.
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache();
        for(AccessibilityWindowInfo window:automation.getWindows()) {
            AccessibilityNodeInfo found=findIn(window.getRoot(),pkg,caption);
            if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo findIn(AccessibilityNodeInfo node,String pkg,String caption) {
        if(node==null)return null;
        if(node.isVisibleToUser() && pkg.contentEquals(String.valueOf(node.getPackageName())) &&
            (caption.contentEquals(String.valueOf(node.getText())) || caption.contentEquals(String.valueOf(node.getContentDescription()))))return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=findIn(node.getChild(i),pkg,caption);if(found!=null)return found; }
        return null;
    }
    private Rect key(String caption) {
        AccessibilityNodeInfo node=find("com.xiaomi.type",caption);
        if(node==null)throw new AssertionError("Keyboard key missing: " + caption);
        Rect rect=new Rect();node.getBoundsInScreen(rect);return rect;
    }
    private Rect keyBox(String caption) {
        AccessibilityNodeInfo node=find("com.xiaomi.type",caption);
        if(node==null)throw new AssertionError("Keyboard key missing: " + caption);
        Rect rect=new Rect();node.getBoundsInScreen(rect);
        AccessibilityNodeInfo parent=node.getParent();
        if(parent!=null) {Rect p=new Rect();parent.getBoundsInScreen(p);if(p.width()<300 && p.height()<600 && p.width()>rect.width())rect=p;}
        return rect;
    }
    private void press(String caption) {Rect rect=key(caption);tap(rect.centerX(),rect.centerY());SystemClock.sleep(250);}
    private void swipe(String caption) {
        if(!imeVisible())showKeyboard();
        Rect rect=key(caption);float distance=58*getTargetContext().getResources().getDisplayMetrics().density;
        received.clear();gesture(rect.centerX(),rect.centerY(),rect.centerX(),rect.centerY()+distance,350);SystemClock.sleep(650);
    }
    private void edit(String text,int position) {
        runOnMainSync(() -> {input.setText(text);input.setSelection(position);});SystemClock.sleep(200);
    }
    private String text() {final String[] value=new String[1];runOnMainSync(() ->value[0]=input.getText().toString());return value[0];}
    private int selection(boolean end) {final int[] value=new int[1];runOnMainSync(() ->value[0]=end?input.getSelectionEnd():input.getSelectionStart());return value[0];}
    private void tapModule(String caption) {
        AccessibilityNodeInfo node=find("io.mo.xatype",caption);
        if(node==null)throw new AssertionError("Module control missing: " + caption);
        Rect rect=new Rect();node.getBoundsInScreen(rect);tap(rect.centerX(),rect.centerY());SystemClock.sleep(300);
    }
    private void oneHandSwitches() throws Exception {
        configure("pref_keyboard_shortcuts",true);
        configure("pref_one_hand_side",0);
        configure("pref_one_hand_last_side",2);
        configure("pref_one_hand_width",80);
        configure("pref_shortcut_labels",true);
        // Old saved bindings must display the new short labels and continue working.
        configure("pref_shortcut_map_26","q=左手模式,w=右手模式,d=Esc");
        restartKeyboard();
        edit("",0);
        Rect baselineQ=keyBox("Q"),baselineP=keyBox("P");
        int full=baselineP.right-baselineQ.left;
        String[] pid={shell("pidof com.xiaomi.type").trim()};
        test("single menu button defaults to right and toggles off",() -> {
            menuSwitch("单手模式");assertOneHand(2,full,baselineQ,baselineP);
            menuSwitch("单手模式");assertOneHand(0,full,baselineQ,baselineP);
        });
        test("menu remembers left after switching off",() -> {
            swipe("Q");assertOneHand(1,full,baselineQ,baselineP);
            menuSwitch("单手模式");assertOneHand(0,full,baselineQ,baselineP);
            menuSwitch("单手模式");assertOneHand(1,full,baselineQ,baselineP);snapshot("menu-left");
            menuSwitch("单手模式");assertOneHand(0,full,baselineQ,baselineP);
            require(prefs.getInt("pref_one_hand_last_side",-1)==1,"left history lost");
        });
        test("menu remembers right across input method restart while off",() -> {
            swipe("W");assertOneHand(2,full,baselineQ,baselineP);
            menuSwitch("单手模式");assertOneHand(0,full,baselineQ,baselineP);
            restartKeyboard();pid[0]=shell("pidof com.xiaomi.type").trim();
            menuSwitch("单手模式");assertOneHand(2,full,baselineQ,baselineP);snapshot("menu-right");
            menuSwitch("单手模式");assertOneHand(0,full,baselineQ,baselineP);
        });
        test("swipe left right and same side off keep editor intact",() -> {
            edit("keep text",4);
            swipe("Q");assertOneHand(1,full,baselineQ,baselineP);
            swipe("W");assertOneHand(2,full,baselineQ,baselineP);snapshot("swipe-right");
            swipe("W");assertOneHand(0,full,baselineQ,baselineP);
            require("keep text".equals(text()) && selection(false)==4,"layout action edited text or selection");
        });
        test("swipe preserves pending composition and narrow key hit targets",() -> {
            edit("",0);press("N");String before=text();
            swipe("Q");assertOneHand(1,full,baselineQ,baselineP);
            require(before.equals(text()),"switch committed pending composition");
            press("I");swipe("D");require(text().isEmpty(),"narrow keys failed or composition was committed");
            swipe("Q");assertOneHand(0,full,baselineQ,baselineP);
        });
        test("live switches do not restart the input method",() ->
            require(pid[0].equals(shell("pidof com.xiaomi.type").trim()),"host process changed"));
        edit("",0);
    }
    private void assertOneHand(int side,int full,Rect baselineQ,Rect baselineP) throws Exception {
        Rect q=keyBox("Q"),p=keyBox("P");float ratio=(p.right-q.left)/(float)full;
        for(int i=0;i<25 && (Math.abs(ratio-(side==0?1f:.8f))>=.055f ||
            (side==1 && Math.abs(q.left-baselineQ.left)>=60) ||
            (side==2 && Math.abs(p.right-baselineP.right)>=60));i++) {
            SystemClock.sleep(120);q=keyBox("Q");p=keyBox("P");ratio=(p.right-q.left)/(float)full;
        }
        status("Geometry side="+side+" Q="+q+" P="+p+" baselineQ="+baselineQ+" baselineP="+baselineP);
        snapshot("geometry-side-"+side);
        require(Math.abs(ratio-(side==0?1f:.8f))<.055f,"side="+side+" measured ratio="+ratio);
        require(side!=1 || Math.abs(q.left-baselineQ.left)<60,"left edge mismatch");
        require(side!=2 || Math.abs(p.right-baselineP.right)<60,"right edge mismatch");
        require(prefs.getInt("pref_one_hand_side",-1)==side,"direction not persisted");
    }
    private void menuSwitch(String caption) throws Exception {
        Rect q=keyBox("Q"),p=keyBox("P");
        tap(q.left+q.width()*.7f,q.top-q.height()*.45f);SystemClock.sleep(400);
        for(int page=0;page<4 && find("com.xiaomi.type",caption)==null;page++) {
            Bitmap screen=automation.takeScreenshot();int width=screen.getWidth(),height=screen.getHeight();screen.recycle();
            gesture(q.left+(p.right-q.left)*.8f,height*.81f,q.left+(p.right-q.left)*.2f,height*.81f,350);SystemClock.sleep(500);
        }
        require(find("com.xiaomi.type","左手模式")==null && find("com.xiaomi.type","右手模式")==null,
            "old directional menu buttons remain");
        snapshot("menu-entries");press(caption);SystemClock.sleep(650);
        for(int i=0;i<25 && find("com.xiaomi.type","Q")==null;i++) SystemClock.sleep(120);
        snapshot("menu-return");
        require(find("com.xiaomi.type","Q")!=null,"menu did not return to keyboard");
    }
    private void suite() throws Exception {
        hideKeyboard();
        test("test text survives tab switches",() -> {edit("qa-123",6);tapModule("键盘");tapModule("外观");require("qa-123".equals(text()),"text lost");tapModule("概览");});
        test("clear test input",() -> {Rect clear=bounds(activity.findViewById(io.mo.xatype.R.id.clearKeyboardTest));tap(clear.centerX(),clear.centerY());require(text().isEmpty(),"clear did not erase test text");});
        configure("pref_keyboard_shortcuts",true);configure("pref_shortcut_labels",true);
        configure("pref_one_hand_side",0);
        configure("pref_shortcut_map_26","q=向左,w=向右,e=向上,r=向下,a=撤销,s=恢复,d=Esc,f=按键:Ctrl+A,g=按键:Ctrl+Shift+Z,h=按键:Ctrl+Y,j=按键:Alt+Left,k=Tab,l=Enter");
        restartKeyboard();
        runOnMainSync(() ->input.setOnKeyListener((view,code,event)->{received.add(new KeyEvent(event));return false;}));
        snapshot("shortcuts-configured");
        test("left and right arrows move editor caret",() -> {edit("123\n123\n123",5);swipe("Q");require(selection(false)==4,"left position="+selection(false));swipe("W");require(selection(false)==5,"right position="+selection(false));});
        test("up and down arrows move across lines",() -> {edit("123\n123\n123",5);swipe("E");require(selection(false)==1,"up position="+selection(false));swipe("R");require(selection(false)==5,"down position="+selection(false));});
        test("custom Ctrl A selects whole editor",() -> {edit("abc xyz",7);swipe("F");require(selection(false)==0 && selection(true)==7,"selection="+selection(false)+":"+selection(true));require("abc xyz".equals(text()),"shortcut inserted text");});
        test("undo and redo use editor history",() -> {
            edit("base",4);runOnMainSync(() ->input.onCreateInputConnection(new EditorInfo()).commitText("X",1));
            require("baseX".equals(text()),"test insertion failed");swipe("A");require("base".equals(text()),"undo="+text());
            swipe("S");require("baseX".equals(text()),"redo="+text());swipe("A");swipe("G");require("baseX".equals(text()),"custom redo="+text());
        });
        test("Esc reaches editor when idle",() -> {edit("qa",2);swipe("D");require(received.stream().anyMatch(e->e.getKeyCode()==KeyEvent.KEYCODE_ESCAPE && e.getAction()==KeyEvent.ACTION_DOWN),"Esc down missing");});
        test("Alt Left chord carries modifier",() -> {edit("abc xyz",7);swipe("J");require(received.stream().anyMatch(e->e.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT && e.isAltPressed()),"Alt modifier missing");});
        test("Enter inserts newline in multiline test editor",() -> {edit("abc",3);swipe("L");require("abc\n".equals(text()),"Enter result="+text());});
        edit("",0);Rect baselineQ=keyBox("Q"),baselineP=keyBox("P");int baselineWidth=baselineP.right-baselineQ.left;
        for(int side:new int[]{1,2})for(int width:new int[]{70,80,90}) {
            final int direction=side,percent=width;
            test("one hand side="+side+" width="+width,() -> {
                configure("pref_one_hand_side",direction);configure("pref_one_hand_width",percent);restartKeyboard();
                Rect q=keyBox("Q"),p=keyBox("P");float ratio=(p.right-q.left)/(float)baselineWidth;
                snapshot("onehand-"+direction+"-"+percent);
                require(Math.abs(ratio-percent/100f)<0.055f,"measured width ratio="+ratio+" Q="+q+" P="+p);
                require(direction==1?Math.abs(q.left-baselineQ.left)<60:Math.abs(p.right-baselineP.right)<60,"keyboard did not align to chosen edge");
                press("N");swipe("D");require(text().isEmpty(),"narrow layout hit target mismatch: "+text());
            });
        }
        shell("am start -W -n io.mo.xatype/.ui.MainActivity");SystemClock.sleep(700);
        runOnMainSync(() ->input=activity.findViewById(io.mo.xatype.R.id.keyboardTestInput));
        edit("",0);
    }


    private void status(String message) {
        Bundle report = new Bundle(); report.putString("stream", message + "\n"); sendStatus(0, report);
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             java.io.FileInputStream input = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count=input.read(buffer))>=0) bytes.write(buffer,0,count);
            return bytes.toString("UTF-8");
        }
    }

    private Rect bounds(View view) {
        final Rect[] result = new Rect[1];
        runOnMainSync(() -> { int[] p = new int[2]; view.getLocationOnScreen(p); result[0] = new Rect(p[0],p[1],p[0]+view.getWidth(),p[1]+view.getHeight()); });
        return result[0];
    }

    private void tap(float x, float y) {
        gesture(x,y,x,y,65);
    }

    private void gesture(float x, float y, float endX, float endY, long duration) {
        final boolean[] focused = new boolean[1];
        runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
        require(focused[0], "Module lost foreground; refusing to inject into another app");
        long start = SystemClock.uptimeMillis();
        for (int step = 0; step <= 12; step++) {
            float fraction = step / 12f;
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                x+(endX-x)*fraction,y+(endY-y)*fraction,0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            automation.injectInputEvent(event,true); event.recycle();
            SystemClock.sleep(duration/12);
        }
    }

    private void snapshot(String name) throws Exception {
        Bitmap bitmap = automation.takeScreenshot();
        if (bitmap != null) {
            try (FileOutputStream stream = new FileOutputStream(new File(directory,name+".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);
            }
            bitmap.recycle();
        }
        JSONArray nodes = new JSONArray();
        for (AccessibilityWindowInfo window : automation.getWindows()) visit(window.getRoot(),nodes);
        try (FileOutputStream stream = new FileOutputStream(new File(directory,name+".json"))) {
            stream.write(nodes.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void visit(AccessibilityNodeInfo node, JSONArray result) throws Exception {
        if (node == null) return;
        String pkg = String.valueOf(node.getPackageName());
        if (pkg.equals("io.mo.xatype") || pkg.equals("com.xiaomi.type")) {
            Rect rect = new Rect(); node.getBoundsInScreen(rect);
            result.put(new JSONObject().put("package",pkg).put("text",String.valueOf(node.getText()))
                .put("description",String.valueOf(node.getContentDescription())).put("id",String.valueOf(node.getViewIdResourceName()))
                .put("bounds",rect.flattenToString()).put("clickable",node.isClickable()));
        }
        for (int i=0;i<node.getChildCount();i++) visit(node.getChild(i),result);
    }
}
