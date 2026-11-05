"""Declare the reference IDs used by keyboard bridges (IDs are never runtime fallbacks).

After update-keyboard-abi-reference.ps1 on the 0.2.1053 APK, run
keyboard-abi-1053-settings-owner.py on its output.
"""
import json
import sys

groups = {}


def group(name, classes, methods=None, fields=None):
    groups[name] = {"classes": classes.split(), "methods": methods or {}, "fields": fields or {}}


group("keyboard.engine", "z8.g z8.q z8.p z8.r z8.u z8.b z8.t z8.a z8.s z8.e y8.b nc.f ab.x0 ab.f1 ab.s0 ab.b1 ka.c ka.f",
      {"z8.q": "a b clear d destroy e f g i j k o q s t u", "z8.p": "c h l m n p r v",
       "ab.x0": "b", "ab.f1": "c", "ab.s0": "l", "ab.b1": "u o K y h"}, {"ka.c": "a f"})
group("keyboard.layout", "ka.c ka.f ka.e ka.g ab.p1 z8.g la.a ab.b1 ab.f1",
      {"ab.p1": "e H G", "ab.b1": "m A u o K y", "ab.f1": "c"},
      {"ka.c": "a b c d e f g h i j k l m n o p q r", "ka.e": "a b c", "ka.g": "a b c d e f g h i"})
group("keyboard.actions", "ab.b1 z8.q",
      {"ab.b1": "n d f M b K u F J", "z8.q": "clear"})
group("keyboard.picker", "z9.g6 bd.b s0.p z9.u7 oc.m gb.u0 z9.c3",
      {"bd.b": "g", "z9.u7": "a0", "oc.m": "T0", "z9.c3": "m"}, {"z9.g6": "a c"})
group("keyboard.settings", "db.l dg.u androidx.preference.Preference miuix.preference.SingleChoicePreference androidx.preference.PreferenceGroup qh.c bd.b z8.g com.mi.ime.MiInputMethodService",
      {"db.l": "f h requireContext", "androidx.preference.Preference": "D G", "androidx.preference.PreferenceGroup": "L",
       "qh.c": "y", "bd.b": "g", "com.mi.ime.MiInputMethodService": "isEngineManagerReady$app_iflytekFullRelease switchEngine$app_iflytekFullRelease"},
      {"miuix.preference.SingleChoicePreference": "n0", "androidx.preference.Preference": "j", "com.mi.ime.MiInputMethodService": "instance"})
group("keyboard.swipe", "ka.c e1.o bd.b nc.m s0.v0 s0.h2 s0.o0 s0.d s0.c2 androidx.compose.ui.layout.a androidx.compose.ui.draw.a d2.u0 s0.p s0.i1 a2.o c2.f0 w5.i l1.b l1.h0 z9.w5 p0.n d2.w",
      {"bd.b": "g", "s0.h2": "getValue", "s0.v0": "setValue", "s0.d": "R", "androidx.compose.ui.layout.a": "b",
       "androidx.compose.ui.draw.a": "c", "s0.p": "l", "a2.o": "i v l", "c2.f0": "a c S", "w5.i": "s",
       "l1.h0": "v", "z9.w5": "a", "p0.n": "a", "d2.w": "dispatchTouchEvent"},
      {"ka.c": "a", "nc.m": "a", "s0.o0": "f", "d2.u0": "f", "l1.b": "a"})
group("keyboard.letterAppearance", "ka.c s0.p e1.o e1.g z9.w5 ma.v androidx.compose.foundation.layout.a e1.b",
      {"z9.w5": "a", "ma.v": "Q h0", "androidx.compose.foundation.layout.a": "a"}, {"e1.b": "b c"})
group("keyboard.surfaceText", "z9.u7 z9.q1 qh.c p0.n ma.k",
      {"z9.u7": "n S i0 t0", "z9.q1": "b", "qh.c": "h", "p0.n": "a b"})

if __name__ == "__main__":
    with open(sys.argv[1], "w", encoding="utf-8") as output:
        json.dump(groups, output, ensure_ascii=False, indent=2)
