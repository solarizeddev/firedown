#!/usr/bin/env python3
"""A simulated phone for selftest.sh: answers the adb commands
update-install-test.sh sends, the way Firedown, Android's package installer
and the notification shade would. Every call is a new process, so the phone's
state lives in a JSON file in $FAKE_PHONE_DIR.

What it simulates, and only that:
  - the install: the copy takes COPY_S seconds (PREPARING 0..100), then
    INSTALLING; a file that is not an APK is refused (FAILED), a missing
    file fails at once;
  - a real APK: on Android 12+ (FAKE_SDK, default 34) Android installs it
    without asking, the app dies before Android's answer reaches it (no
    IDLE in the trace) and posts "Firedown updated"; below 12, or with
    FAKE_DIALOG=1, Android's dialog appears (state IDLE), cancelling it
    posts the "update failed" notification, confirming it replaces the app;
  - a second install request while one runs logs "install already running";
  - the sheet's footer renders (UpdateSheetTrace), and a font size change
    rebuilds the sheet;
  - what makes a real phone hard to read: dumpsys output and the log run to
    megabytes (a `producer | grep -q` misses a match under pipefail), the
    screen cannot be dumped while the progress bar moves, and FAKE_SHADE=stuck
    makes the shade ignore `cmd statusbar` (a swipe still opens it), and
    FAKE_SHADE=touched closes it under the first read, with the touchscreen
    event a Samsung logs;
  - the notification permission (FAKE_NOTIF_GRANTED, default 1), with the
    grants and revokes the script made.
The trace lines and the script's markers go to a log that `adb logcat`
streams, like the real one.
"""
import fcntl
import json
import os
import re
import sys
import time

PKG = "com.solarized.firedown"
DIR = os.environ["FAKE_PHONE_DIR"]
STATE = os.path.join(DIR, "phone.json")
LOG = os.path.join(DIR, "logcat.txt")
COPY_S = float(os.environ.get("FAKE_COPY_S", "3"))
SDK = int(os.environ.get("FAKE_SDK", "34"))
DIALOG = os.environ.get("FAKE_DIALOG", "") == "1" or SDK < 31
SHADE = os.environ.get("FAKE_SHADE", "")
# FAKE_SHEET=touched: a finger lands outside the first sheet the script
# reads and closes it, as Back does; =touched-twice does it to the next
# one too.
SHEET = os.environ.get("FAKE_SHEET", "")
NOTIF_GRANTED = os.environ.get("FAKE_NOTIF_GRANTED", "1") == "1"
ANSWER_S = 0.5
APK_PATH = "/storage/emulated/0/Android/data/%s/files/firedown.apk" % PKG
STAGE = "/data/local/tmp/fd-update-test.apk"
# A planted fault, so selftest.sh can check the script notices it:
#   cancel-fails  cancelling Android's dialog turns the install FAILED
#   no-progress   the sheet keeps its Install button while copying
#   double        a second install request starts a second install
#   rotate-loses  a sheet rebuilt mid-copy shows the button, not the copy
#   no-updated    after updating itself the app posts no "Firedown updated"
BUG = os.environ.get("FAKE_BUG", "")
# The phone's settings before the run, as "ns/key=value,…" (none: every key
# unset, which Android reads back as "null").
SEED = os.environ.get("FAKE_SETTINGS", "")
# FAKE_GONE_AT_END=1: the phone answers the first `adb get-state` and no
# other (the cable pulled before the script puts the phone back).
GONE_AT_END = os.environ.get("FAKE_GONE_AT_END", "") == "1"
# FAKE_DOWNGRADE=fails: installing an older versionCode is refused, so the
# script cannot put the original version back at the end.
DOWNGRADE = os.environ.get("FAKE_DOWNGRADE", "")
ANIMATION_KEYS = ("global/animator_duration_scale", "global/transition_animation_scale",
                  "global/window_animation_scale")

INSTALL_BTN = (540, 2200)
NOTIF_ROW = (540, 475)
ANDROID_UPDATE = (850, 1550)


def fresh():
    return {
        "installed": 0, "settings": {}, "appops": "default", "files": {},
        "ready": None, "snoozed": False, "focus": "launcher", "behind": "launcher",
        "sheet": False, "sheet_seen": 0, "shade": False, "notifs": {},
        "install": None, "gen": 0, "phase": "IDLE", "percent": -1,
        "notif_install": False, "last_traced": None, "ui": "",
        "sheet_id": "", "seen": 0, "footer": "", "last_render": None,
        "amnesia": False, "notif_perm": NOTIF_GRANTED, "revokes": 0,
        "live": {}, "get_state_calls": 0,
    }


def seed(st):
    """The settings the phone had before any run, and the animation scales
    it runs with (WindowManager's copy, which a settings change updates)."""
    for item in filter(None, SEED.split(",")):
        k, v = item.split("=", 1)
        st["settings"][k] = v
    for k in ANIMATION_KEYS:
        st["live"][k] = st["settings"].get(k, "1")


def set_live(st, key, deleted):
    """WindowManager reads a changed scale again. A deleted key reads as the
    scale in force (WindowManagerService.getAnimatorDurationScaleSetting and
    the window one default to their own field); the transition scale reads
    as the resource default, 1."""
    if key not in ANIMATION_KEYS:
        return
    if not deleted:
        st["live"][key] = st["settings"][key]
    elif key == "global/transition_animation_scale":
        st["live"][key] = "1"


def noise(n):
    """What else a phone logs: enough that a case's log runs past a pipe."""
    with open(LOG, "a") as f:
        for i in range(n):
            f.write("%s  1500  1600 D ActivityManager: %s\n" % (
                time.strftime("%m-%d %H:%M:%S.000"), "background noise " * 12))


def new_sheet(st):
    st["sheet"] = True
    st["sheet_id"] = "%x" % (int(time.time() * 1e6) & 0xfffffff)
    st["last_render"] = None
    render(st)


def render(st):
    """UpdateAvailableSheet.render: the footer for the app's state."""
    if not st["sheet"]:
        return
    phase = st["phase"]
    busy = phase in ("PREPARING", "INSTALLING")
    if st["amnesia"] or (busy and BUG == "no-progress"):
        footer = "button"
    elif busy:
        st["seen"] = st["gen"]
        footer = "progress" if phase == "PREPARING" else "installing"
    else:
        ours = st["seen"] > 0 and st["gen"] == st["seen"]
        if ours and phase == "IDLE":
            footer = "closed"
        elif ours and phase == "FAILED":
            footer = "error"
        else:
            footer = "button"
    key = "%s %s %s %s" % (st["sheet_id"], footer, st["percent"], st["gen"])
    if key == st["last_render"]:
        return
    st["last_render"] = key
    st["footer"] = footer
    log(st, "UpdateSheetTrace", "sheet=%s footer=%s percent=%s gen=%s" % (
        st["sheet_id"], footer, st["percent"], st["gen"]))
    if footer == "closed":
        st["sheet"] = False


def log(st_unused, tag, msg, level="D"):
    with open(LOG, "a") as f:
        f.write("%s  1234  1300 %s %s: %s\n" % (time.strftime("%m-%d %H:%M:%S.000"), level, tag, msg))


def trace(st):
    key = "%s %s %s" % (st["phase"], st["percent"], st["gen"])
    if key == st["last_traced"]:
        return
    st["last_traced"] = key
    log(st, "UpdateInstallTrace", "phase=%s percent=%s gen=%s notif=%s" % (
        st["phase"], st["percent"], st["gen"], "true" if st["notif_install"] else "false"))


def advance(st):
    """Moves a running install along by the clock."""
    ins = st["install"]
    if ins is None:
        return
    t = time.time() - ins["t0"]
    copy = ins["copy"]
    if st["phase"] == "PREPARING":
        pct = min(100, int(t / copy * 100))
        for step in range(10, 101, 10):
            if st["percent"] < step <= pct:
                st["percent"] = step
                trace(st)
                render(st)
        if st["notif_install"]:
            st["notifs"]["666"] = "progress:%d/100" % st["percent"]
        if t >= copy:
            st["phase"], st["percent"] = "INSTALLING", -1
            trace(st)
            render(st)
            if st["notif_install"]:
                st["notifs"]["666"] = "indeterminate"
    if st["phase"] == "INSTALLING" and t >= copy + ANSWER_S:
        if ins["kind"] == "apk" and not DIALOG:
            # Installed without asking: the app is replaced before
            # Android's answer reaches it, then posts "Firedown updated".
            code = st["ready"]["code"]
            kill_app(st)
            st["installed"] = code
            st["ready"] = None
            st["files"].pop(APK_PATH, None)
            st["focus"] = "launcher"
            if BUG != "no-updated":
                st["notifs"]["668"] = "shown"
            return
        if ins["kind"] == "apk":
            st["phase"] = "IDLE"
            trace(st)
            render(st)
            st["notifs"].pop("666", None)
            st["behind"] = st["focus"]
            st["focus"] = "packageinstaller"
            st["sheet"] = False
            st["snoozed"] = True
        else:
            st["phase"] = "FAILED"
            trace(st)
            render(st)
            log(st, "%s.UpdateInstallReceiver" % PKG, "Installation failed: INSTALL_FAILED_INVALID_APK", "E")
            st["notifs"].pop("666", None)
            st["notifs"]["667"] = "shown"
        st["install"] = None


def start_install(st, from_notif):
    if st["phase"] in ("PREPARING", "INSTALLING") and BUG != "double":
        log(st, "%s.UpdateInstaller" % PKG, "install already running")
        return
    st["gen"] += 1
    st["notif_install"] = from_notif
    st["phase"], st["percent"] = "PREPARING", 0
    trace(st)
    render(st)
    kind = st["files"].get(APK_PATH)
    if kind is None:
        st["phase"], st["percent"] = "FAILED", -1
        trace(st)
        render(st)
        st["notifs"]["667"] = "shown"
        return
    st["install"] = {"t0": time.time(), "kind": "apk" if kind.startswith("apk") else "junk"}
    # The large not-an-APK file copies longer, as on a phone.
    st["install"]["copy"] = COPY_S if st["install"]["kind"] == "apk" else 4 * COPY_S
    if from_notif:
        st["notifs"]["666"] = "progress:0/100"


def app_answer(st, action, extras):
    a = action.rsplit(".", 1)[-1]
    if a == "WHERE":
        return "path=%s installed=%d" % (APK_PATH, st["installed"])
    if a == "CLEAR":
        st["ready"] = None
        st["files"].pop(APK_PATH, None)
        st["snoozed"] = False
        for k in ("666", "667", "668"):
            st["notifs"].pop(k, None)
        return "cleared installed=%d" % st["installed"]
    if a == "MARK_READY":
        st["ready"] = {"code": int(extras.get("code", -1)), "name": extras.get("name", "")}
        st["snoozed"] = False
        exists = APK_PATH in st["files"]
        return "ready path=%s size=%d readable=%s installed=%d" % (
            APK_PATH, 100 if exists else -1, "true" if exists else "false", st["installed"])
    if a == "POST_PROMPT":
        st["notifs"]["666"] = "shown"
        st["prompt_name"] = extras.get("name", "")
        return "posted notifications=true"
    if a == "TAP_PROMPT":
        st["notifs"].pop("666", None)
        start_install(st, True)
        return "tapped"
    if a == "STATE":
        n = lambda k: st["notifs"].get(k, "none")
        return "phase=%s percent=%d gen=%d notif=%s installed=%d n666=%s n667=%s n668=%s" % (
            st["phase"], st["percent"], st["gen"], "true" if st["notif_install"] else "false",
            st["installed"], n("666"), n("667"), n("668"))
    return "error unknown action " + action


def node(rid, text, x0, y0, x1, y1, pkg=PKG):
    return '<node index="0" text="%s" resource-id="%s" class="android.view.View" package="%s" bounds="[%d,%d][%d,%d]" />' % (
        text, rid, pkg, x0, y0, x1, y1)


def screen(st):
    nodes = []
    if st["shade"]:
        name = st.get("prompt_name", "")
        if st["notifs"].get("666") == "shown":
            nodes.append(node("android:id/text", "%s is ready. Tap to install" % name, 150, 450, 930, 500, "com.android.systemui"))
    elif st["focus"] == "packageinstaller":
        nodes.append(node("android:id/button1", "Update", 700, 1500, 1000, 1600, "com.google.android.packageinstaller"))
    elif st["focus"] == "app" and st["sheet"]:
        busy = st["footer"] in ("progress", "installing")
        if busy:
            nodes.append(node(PKG + ":id/update_sheet_progress", "", 48, 2090, 1032, 2300))
            label = "Preparing update…" if st["phase"] == "PREPARING" else "Installing…"
            nodes.append(node(PKG + ":id/update_sheet_progress_label", label, 48, 2100, 600, 2160))
            if st["phase"] == "PREPARING":
                nodes.append(node(PKG + ":id/update_sheet_progress_percent", "%d%%" % st["percent"], 900, 2100, 1032, 2160))
        else:
            if st["footer"] == "error":
                nodes.append(node(PKG + ":id/update_sheet_error", "Couldn't start the install. Try again.", 48, 2080, 1032, 2140))
            nodes.append(node(PKG + ":id/update_sheet_install", "Install", 48, 2150, 1032, 2250))
    return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">%s</hierarchy>" % "".join(nodes)


def near(x, y, target):
    return abs(x - target[0]) < 60 and abs(y - target[1]) < 60


def sheet_due(st):
    r = st["ready"]
    return (r is not None and r["code"] > st["installed"] and APK_PATH in st["files"]
            and not st["snoozed"])


def kill_app(st):
    st["install"] = None
    st["phase"], st["percent"], st["gen"] = "IDLE", -1, 0
    st["notif_install"] = False
    st["sheet"] = False
    st["seen"] = 0
    st["amnesia"] = False
    st["footer"] = ""
    st["last_traced"] = None
    for k in ("666", "667", "668"):
        st["notifs"].pop(k, None)
    if st["focus"] == "app":
        st["focus"] = "launcher"


def shell(st, cmd):
    m = re.match(r"run-as \S+ sh -c '(.*)'$", cmd)
    if m:
        cmd = m.group(1)
    elif cmd.startswith("run-as "):
        cmd = cmd.split(" ", 2)[2]
    if cmd == "getprop ro.build.version.sdk":
        return str(SDK)
    if cmd == "getprop ro.build.version.release":
        return {29: "10", 30: "11", 31: "12", 32: "12", 33: "13", 34: "14"}.get(SDK, "?")
    if cmd == "getprop ro.product.manufacturer":
        return "samsung"
    if cmd == "getprop ro.product.model":
        return "SM-FAKE"
    m = re.match(r"settings (put system font_scale \S+|delete system font_scale)$", cmd)
    if m:
        if m.group(1).startswith("put"):
            st["settings"]["system/font_scale"] = cmd.split()[-1]
        else:
            st["settings"].pop("system/font_scale", None)
        # Not a change the browser handles: the activity and the sheet are
        # rebuilt from saved state.
        if st["sheet"] and st["focus"] == "app":
            st["amnesia"] = BUG == "rotate-loses"
            new_sheet(st)
        return ""
    m = re.match(r"settings get (\w+) (\w+)", cmd)
    if m:
        return st["settings"].get(m.group(1) + "/" + m.group(2), "null")
    m = re.match(r"settings delete (\w+) (\w+)", cmd)
    if m:
        st["settings"].pop(m.group(1) + "/" + m.group(2), None)
        set_live(st, m.group(1) + "/" + m.group(2), True)
        return ""
    m = re.match(r"settings put (\w+) (\w+) (\S+)", cmd)
    if m:
        st["settings"][m.group(1) + "/" + m.group(2)] = m.group(3)
        set_live(st, m.group(1) + "/" + m.group(2), False)
        return ""
    if cmd.startswith("appops get"):
        return "REQUEST_INSTALL_PACKAGES: %s; time=+1d" % st["appops"]
    m = re.match(r"appops set \S+ REQUEST_INSTALL_PACKAGES (\w+)", cmd)
    if m:
        st["appops"] = m.group(1)
        return ""
    if cmd.startswith("pm grant") and "POST_NOTIFICATIONS" in cmd:
        st["notif_perm"] = True
        return ""
    if cmd.startswith("pm revoke") and "POST_NOTIFICATIONS" in cmd:
        st["notif_perm"] = False
        st["revokes"] += 1
        return ""
    # A real dumpsys runs to hundreds of KB, most of it after the lines the
    # script wants.
    pad = "\n".join("      padding line %d %s" % (i, "x" * 60) for i in range(1500))
    if cmd.startswith("dumpsys package"):
        return "    versionCode=%d minSdk=26 targetSdk=36\n      android.permission.POST_NOTIFICATIONS: granted=%s\n%s" % (
            st["installed"], "true" if st["notif_perm"] else "false", pad)
    if cmd == "dumpsys window":
        if st["shade"]:
            f = "NotificationShade"
        else:
            f = {"app": PKG + "/" + PKG + ".phone.BrowserMainActivity",
                 "packageinstaller": "com.google.android.packageinstaller/com.android.packageinstaller.PackageInstallerActivity",
                 "launcher": "com.android.launcher3/.Launcher"}[st["focus"]]
        return "  mCurrentFocus=Window{1a2b u0 %s}\n  mFocusedWindow=Window{1a2b u0 %s}\n%s" % (f, f, pad)
    if cmd == "wm size":
        return "Physical size: 1080x2400"
    m = re.match(r"input swipe (\d+) (\d+) (\d+) (\d+)", cmd)
    if m:
        if int(m.group(2)) < 50 and int(m.group(4)) > 800:
            st["shade"] = True
        return ""
    m = re.match(r"log -t (\w+) '(.*)'$", cmd)
    if m:
        log(st, m.group(1), m.group(2), "I")
        return ""
    m = re.match(r"am broadcast -f 32 -n \S+ -a (\S+)(.*)$", cmd)
    if m:
        extras = {}
        for k, v in re.findall(r"--e[is] (\w+) ('[^']*'|\S+)", m.group(2)):
            extras[k] = v.strip("'")
        data = app_answer(st, m.group(1), extras)
        return 'Broadcasting: Intent { act=%s }\nBroadcast completed: result=1, data="%s"' % (m.group(1), data)
    if cmd.startswith("am force-stop"):
        kill_app(st)
        return ""
    if cmd.startswith("am start"):
        st["focus"] = "app"
        if sheet_due(st) and not st["sheet"]:
            new_sheet(st)
        return "Status: ok"
    if cmd == "input keyevent KEYCODE_HOME":
        st["focus"] = "launcher"
        return ""
    if cmd == "input keyevent KEYCODE_BACK":
        if st["focus"] == "packageinstaller":
            st["focus"] = st["behind"]
            log(st, "%s.UpdateInstallReceiver" % PKG, "Installation failed: INSTALL_FAILED_ABORTED", "E")
            st["notifs"]["667"] = "shown"
            if BUG == "cancel-fails":
                st["phase"] = "FAILED"
                trace(st)
                new_sheet(st)
        return ""
    if cmd.startswith("input keyevent") or cmd.startswith("wm dismiss-keyguard"):
        return ""
    if cmd.startswith("uiautomator dump"):
        if st["focus"] == "app" and st["sheet"] and st["footer"] in ("progress", "installing"):
            return "ERROR: could not get idle state."
        # As on a Samsung: a plain dump reads the window Android counts as
        # active, which stays the one under the shade; --windows reads them
        # all (Android 11+).
        touches = {"touched": 1, "touched-twice": 2}.get(SHEET, 0)
        if (st["focus"] == "app" and st["sheet"] and st["footer"] == "button"
                and st.get("sheet_touches", 0) < touches):
            # A finger lands outside the sheet: it closes, snoozed.
            st["sheet_touches"] = st.get("sheet_touches", 0) + 1
            log(st, "InputReader", "Btn_touch(14): value=1 when=1.0")
            st["sheet"] = False
            st["snoozed"] = True
        if st["shade"] and SHADE == "touched" and not st.get("touched"):
            # A finger lands on the shade while it is being read: it closes.
            st["touched"] = True
            log(st, "InputReader", "Btn_touch(14): value=1 when=1.0")
            st["shade"] = False
        if st["shade"] and not ("--windows" in cmd and SDK >= 30):
            st["shade"] = False
            st["ui"] = screen(st)
            st["shade"] = True
        else:
            st["ui"] = screen(st)
        return "UI hierchary dumped to: /sdcard/fd-update-ui.xml"
    m = re.match(r"input tap (\d+) (\d+)", cmd)
    if m:
        x, y = int(m.group(1)), int(m.group(2))
        if st["shade"] and near(x, y, NOTIF_ROW) and st["notifs"].get("666") == "shown":
            st["notifs"].pop("666", None)
            st["shade"] = False
            start_install(st, True)
        elif st["focus"] == "app" and st["sheet"] and near(x, y, INSTALL_BTN):
            start_install(st, False)
        elif st["focus"] == "packageinstaller" and near(x, y, ANDROID_UPDATE):
            st["installed"] = st["ready"]["code"]
            kill_app(st)
            st["ready"] = None
            st["files"].pop(APK_PATH, None)
            st["focus"] = "launcher"
            st["notifs"]["668"] = "shown"
        return ""
    if cmd == "cmd statusbar expand-notifications":
        if SHADE != "stuck":
            st["shade"] = True
        return ""
    if cmd == "cmd statusbar collapse":
        st["shade"] = False
        return ""
    m = re.match(r"cp (\S+) (\S+)$", cmd)
    if m:
        st["files"][m.group(2)] = st["files"].get(m.group(1), "missing")
        return ""
    m = re.match(r"dd if=/dev/zero of=(\S+) ", cmd)
    if m:
        st["files"][m.group(1)] = "junk"
        return ""
    m = re.match(r"rm -f (.*)$", cmd)
    if m:
        for path in m.group(1).split():
            st["files"].pop(path, None)
            if path == "/sdcard/fd-update-ui.xml":
                st["ui"] = ""
        return ""
    if cmd == "df -k /data":
        return "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-48 100000000 50000000 50000000 50% /data"
    return "fake adb: unhandled shell command: " + cmd


def main():
    args = sys.argv[1:]
    if args and args[0] == "logcat":
        pos = 0
        while True:
            if os.path.exists(LOG):
                with open(LOG) as f:
                    f.seek(pos)
                    chunk = f.read()
                    pos = f.tell()
                if chunk:
                    sys.stdout.write(chunk)
                    sys.stdout.flush()
            time.sleep(0.1)
    with open(STATE + ".lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if os.path.exists(STATE):
            st = json.load(open(STATE))
        else:
            st = fresh()
            seed(st)
        advance(st)
        noise(3)
        out = ""
        rc = 0
        if args == ["get-state"]:
            st["get_state_calls"] += 1
            if GONE_AT_END and st["get_state_calls"] > 1:
                sys.stderr.write("error: no devices/emulators found\n")
                rc = 1
            else:
                out = "device"
        elif args == ["get-serialno"]:
            out = "FAKE0123"
        elif args and args[0] == "shell":
            out = shell(st, " ".join(args[1:]))
            if out.startswith("fake adb: unhandled"):
                sys.stderr.write(out + "\n")
                with open(os.path.join(DIR, "unhandled.txt"), "a") as f:
                    f.write(out + "\n")
                out = ""
        elif args[:2] == ["exec-out", "cat"]:
            out = st["ui"]
        elif args[:2] == ["exec-out", "screencap"]:
            out = "PNG"
        elif args and args[0] == "push":
            m = re.search(r"versionCode=(\d+)", open(args[1]).read())
            st["files"][args[2]] = "apk:%s" % (m.group(1) if m else "?")
            out = "1 file pushed"
        elif args and args[0] == "install":
            m = re.search(r"versionCode=(\d+)", open(args[-1]).read())
            if m and DOWNGRADE == "fails" and int(m.group(1)) < st["installed"]:
                out = "Performing Streamed Install\nadb: failed to install: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"
                rc = 1
            elif m:
                kill_app(st)
                st["installed"] = int(m.group(1))
                out = "Performing Streamed Install\nSuccess"
            else:
                out = "Failure [INSTALL_FAILED_INVALID_APK]"
                rc = 1
        else:
            sys.stderr.write("fake adb: unhandled: %s\n" % " ".join(args))
            with open(os.path.join(DIR, "unhandled.txt"), "a") as f:
                f.write(" ".join(args) + "\n")
            rc = 1
        json.dump(st, open(STATE, "w"))
    if out:
        sys.stdout.write(out + "\n")
    sys.exit(rc)


if __name__ == "__main__":
    main()
