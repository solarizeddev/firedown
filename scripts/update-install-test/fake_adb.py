#!/usr/bin/env python3
"""A simulated phone for selftest.sh: answers the adb commands
update-install-test.sh sends, the way Firedown, Android's package installer
and the notification shade would. Every call is a new process, so the phone's
state lives in a JSON file in $FAKE_PHONE_DIR.

What it simulates, and only that:
  - the install: the copy takes COPY_S seconds (PREPARING 0..100), then
    INSTALLING; a real APK then brings Android's dialog (state IDLE), a file
    that is not one is refused (FAILED), a missing file fails at once;
  - a second install request while one runs logs "install already running";
  - cancelling Android's dialog posts the "update failed" notification, as
    the app does today;
  - confirming it replaces the app and posts "Firedown updated".
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
ANSWER_S = 0.5
APK_PATH = "/storage/emulated/0/Android/data/%s/files/firedown.apk" % PKG
STAGE = "/data/local/tmp/fd-update-test.apk"
# A planted fault, so selftest.sh can check the script notices it:
#   cancel-fails  cancelling Android's dialog turns the install FAILED
#   no-progress   the sheet keeps its Install button while copying
#   double        a second install request starts a second install
BUG = os.environ.get("FAKE_BUG", "")

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
    }


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
        for step in (25, 50, 75, 100):
            if st["percent"] < step <= pct:
                st["percent"] = step
                trace(st)
        if st["notif_install"]:
            st["notifs"]["666"] = "progress:%d/100" % st["percent"]
        if t >= copy:
            st["phase"], st["percent"] = "INSTALLING", -1
            trace(st)
            if st["notif_install"]:
                st["notifs"]["666"] = "indeterminate"
    if st["phase"] == "INSTALLING" and t >= copy + ANSWER_S:
        if ins["kind"] == "apk":
            st["phase"] = "IDLE"
            trace(st)
            st["notifs"].pop("666", None)
            st["behind"] = st["focus"]
            st["focus"] = "packageinstaller"
            st["sheet"] = False
            st["snoozed"] = True
        else:
            st["phase"] = "FAILED"
            trace(st)
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
    kind = st["files"].get(APK_PATH)
    if kind is None:
        st["phase"], st["percent"] = "FAILED", -1
        trace(st)
        st["notifs"]["667"] = "shown"
        return
    st["install"] = {"t0": time.time(), "kind": "apk" if kind.startswith("apk") else "junk"}
    # The large not-an-APK file copies longer, as on a phone.
    st["install"]["copy"] = COPY_S if st["install"]["kind"] == "apk" else 2 * COPY_S
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
        busy = (st["phase"] in ("PREPARING", "INSTALLING") and not st["notif_install"]
                and BUG != "no-progress")
        if busy:
            nodes.append(node(PKG + ":id/update_sheet_progress", "", 48, 2090, 1032, 2300))
            label = "Preparing update…" if st["phase"] == "PREPARING" else "Installing…"
            nodes.append(node(PKG + ":id/update_sheet_progress_label", label, 48, 2100, 600, 2160))
            if st["phase"] == "PREPARING":
                nodes.append(node(PKG + ":id/update_sheet_progress_percent", "%d%%" % st["percent"], 900, 2100, 1032, 2160))
        else:
            if st["phase"] == "FAILED":
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
        return "34"
    m = re.match(r"settings get (\w+) (\w+)", cmd)
    if m:
        return st["settings"].get(m.group(1) + "/" + m.group(2), "null")
    m = re.match(r"settings delete (\w+) (\w+)", cmd)
    if m:
        st["settings"].pop(m.group(1) + "/" + m.group(2), None)
        return ""
    m = re.match(r"settings put (\w+) (\w+) (\S+)", cmd)
    if m:
        st["settings"][m.group(1) + "/" + m.group(2)] = m.group(3)
        return ""
    if cmd.startswith("appops get"):
        return "REQUEST_INSTALL_PACKAGES: %s; time=+1d" % st["appops"]
    m = re.match(r"appops set \S+ REQUEST_INSTALL_PACKAGES (\w+)", cmd)
    if m:
        st["appops"] = m.group(1)
        return ""
    if cmd.startswith("pm grant") or cmd.startswith("pm revoke"):
        return ""
    if cmd.startswith("dumpsys package"):
        return "    versionCode=%d minSdk=26 targetSdk=36\n      android.permission.POST_NOTIFICATIONS: granted=false" % st["installed"]
    if cmd == "dumpsys window":
        f = {"app": PKG + "/" + PKG + ".phone.BrowserMainActivity",
             "packageinstaller": "com.google.android.packageinstaller/com.android.packageinstaller.PackageInstallerActivity",
             "launcher": "com.android.launcher3/.Launcher"}[st["focus"]]
        return "  mCurrentFocus=Window{1a2b u0 %s}" % f
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
        if sheet_due(st):
            st["sheet"] = True
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
                st["sheet"] = True
        return ""
    if cmd.startswith("input keyevent") or cmd.startswith("wm dismiss-keyguard"):
        return ""
    if cmd.startswith("uiautomator dump"):
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
        st = json.load(open(STATE)) if os.path.exists(STATE) else fresh()
        advance(st)
        out = ""
        rc = 0
        if args == ["get-state"]:
            out = "device"
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
            if m:
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
