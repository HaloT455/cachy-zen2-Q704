#!/usr/bin/env python3
import ast
import os
import signal
import subprocess
import sys
import threading
import time

def resolve_atspi_bus():
    existing = os.environ.get("AT_SPI_BUS_ADDRESS", "").strip()
    if existing:
        print(f"[Q704-FOCUS] AT-SPI bus from env: {existing}", flush=True)
        return existing

    last_error = None
    for attempt in range(1, 11):
        try:
            raw = subprocess.check_output(
                [
                    "/usr/bin/gdbus", "call", "--session",
                    "--dest", "org.a11y.Bus",
                    "--object-path", "/org/a11y/bus",
                    "--method", "org.a11y.Bus.GetAddress",
                ],
                text=True,
                stderr=subprocess.STDOUT,
                timeout=3,
            ).strip()

            parsed = ast.literal_eval(raw)
            address = parsed[0] if isinstance(parsed, tuple) else str(parsed)
            address = str(address).strip()

            if address:
                os.environ["AT_SPI_BUS_ADDRESS"] = address
                print(
                    f"[Q704-FOCUS] AT-SPI bus resolved on attempt {attempt}: {address}",
                    flush=True,
                )
                return address
        except Exception as ex:
            last_error = ex
            print(
                f"[Q704-FOCUS] waiting for AT-SPI bus attempt {attempt}/10: {ex}",
                flush=True,
            )
            time.sleep(0.5)

    raise RuntimeError(f"cannot resolve AT-SPI bus: {last_error}")

resolve_atspi_bus()

import pyatspi

if len(sys.argv) != 2:
    raise SystemExit("usage: q704_focusd.py <keyboard-pid>")

PARENT_PID = int(sys.argv[1])

_last_visible = False
_last_desc = ""
_hide_misses = 0
_lock = threading.Lock()

EDITABLE_ROLES = {
    getattr(pyatspi, "ROLE_ENTRY", -1001),
    getattr(pyatspi, "ROLE_PASSWORD_TEXT", -1002),
    getattr(pyatspi, "ROLE_TEXT", -1003),
    getattr(pyatspi, "ROLE_PARAGRAPH", -1004),
    getattr(pyatspi, "ROLE_DOCUMENT_TEXT", -1005),
}

def parent_alive():
    try:
        os.kill(PARENT_PID, 0)
        return True
    except OSError:
        return False

def send(sig):
    if not parent_alive():
        raise SystemExit(0)
    try:
        os.kill(PARENT_PID, sig)
    except ProcessLookupError:
        raise SystemExit(0)

def describe(obj):
    try:
        role = obj.getRoleName()
    except Exception:
        role = "?"
    try:
        name = obj.name or ""
    except Exception:
        name = ""
    try:
        app = obj.getApplication()
        appname = app.name if app else ""
    except Exception:
        appname = ""
    return f"app={appname!r} role={role} name={name!r}"

def state_has(obj, state_const):
    try:
        return obj.getState().contains(state_const)
    except Exception:
        return False

def is_editable(obj):
    if obj is None:
        return False

    # Strongest signal for Chrome/Telegram/web contenteditable.
    if state_has(obj, pyatspi.STATE_EDITABLE):
        return True

    # Native entry/password controls sometimes omit EDITABLE momentarily.
    try:
        role = obj.getRole()
        if role in (
            getattr(pyatspi, "ROLE_ENTRY", -2001),
            getattr(pyatspi, "ROLE_PASSWORD_TEXT", -2002),
        ):
            return True

        # Text/paragraph/document roles are accepted only when focused and
        # selectable/sensitive. This catches chat composers and contenteditable
        # without opening the keyboard on ordinary document text.
        if role in EDITABLE_ROLES:
            focused = state_has(obj, pyatspi.STATE_FOCUSED)
            sensitive = state_has(obj, pyatspi.STATE_SENSITIVE)
            selectable = state_has(obj, pyatspi.STATE_SELECTABLE_TEXT)
            if focused and (sensitive or selectable):
                return True
    except Exception:
        pass

    return False

def emit_visibility(show, obj=None, reason="event"):
    global _last_visible, _last_desc, _hide_misses
    with _lock:
        if show:
            _hide_misses = 0
            desc = describe(obj) if obj is not None else ""
            if not _last_visible or desc != _last_desc:
                print(f"[Q704-FOCUS] SHOW/{reason} {desc}", flush=True)
                send(signal.SIGUSR1)
            _last_visible = True
            _last_desc = desc
        else:
            _hide_misses += 1
            # Debounce focus transitions (input -> popup -> input).
            if _last_visible and _hide_misses >= 3:
                print(f"[Q704-FOCUS] HIDE/{reason}", flush=True)
                send(signal.SIGUSR2)
                _last_visible = False
                _last_desc = ""
                _hide_misses = 0

def on_focus(event):
    try:
        focused = bool(event.detail1)
        obj = event.source
    except Exception:
        return

    if focused and is_editable(obj):
        emit_visibility(True, obj, "focus")
    elif focused:
        emit_visibility(False, obj, "focus-other")

def on_caret(event):
    obj = getattr(event, "source", None)
    if is_editable(obj):
        emit_visibility(True, obj, "caret")

def on_editable(event):
    obj = getattr(event, "source", None)
    try:
        enabled = bool(event.detail1)
    except Exception:
        enabled = False

    if enabled and is_editable(obj):
        emit_visibility(True, obj, "editable")

def on_text_insert(event):
    obj = getattr(event, "source", None)
    if is_editable(obj):
        emit_visibility(True, obj, "text")

def find_focused_descendant(root, max_nodes=5000):
    if root is None:
        return None

    stack = [root]
    visited = 0

    while stack and visited < max_nodes:
        obj = stack.pop()
        visited += 1

        try:
            state = obj.getState()
            if state.contains(pyatspi.STATE_FOCUSED):
                return obj

            count = obj.childCount
            for index in range(count - 1, -1, -1):
                child = obj.getChildAtIndex(index)
                if child is not None:
                    stack.append(child)
        except Exception:
            continue

    return None

def currently_focused_object():
    try:
        desktop = pyatspi.Registry.getDesktop(0)
    except Exception:
        return None

    # Prefer active windows so polling remains cheap on Chrome/Telegram.
    for ai in range(desktop.childCount):
        try:
            app = desktop.getChildAtIndex(ai)
            if app is None:
                continue

            for wi in range(app.childCount):
                win = app.getChildAtIndex(wi)
                if win is None:
                    continue

                try:
                    wstate = win.getState()
                    active = wstate.contains(pyatspi.STATE_ACTIVE)
                    showing = wstate.contains(pyatspi.STATE_SHOWING)
                    if not (active or showing):
                        continue
                except Exception:
                    pass

                focused = find_focused_descendant(win)
                if focused is not None:
                    return focused
        except Exception:
            continue

    return None


def dbus_input_monitor_loop():
    print("[Q704-FOCUS] DBus input monitor started", flush=True)

    while parent_alive():
        proc = None
        try:
            proc = subprocess.Popen(
                [
                    "/usr/bin/dbus-monitor",
                    "--session",
                    "type='method_call'",
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
            )

            for raw in proc.stdout:
                if not parent_alive():
                    try:
                        proc.terminate()
                    except Exception:
                        pass
                    return

                line = raw.strip()

                is_fcitx = "interface=org.fcitx.Fcitx.InputContext1" in line
                is_ibus = "interface=org.freedesktop.IBus.InputContext" in line

                if not (is_fcitx or is_ibus):
                    continue

                if "member=FocusIn" in line or "member=focus_in" in line:
                    print(f"[Q704-FOCUS] DBUS SHOW {line}", flush=True)
                    emit_visibility(True, None, "dbus-focus")
                elif "member=FocusOut" in line or "member=focus_out" in line:
                    print(f"[Q704-FOCUS] DBUS HIDE {line}", flush=True)
                    emit_visibility(False, None, "dbus-focus")
                elif (
                    "member=SetCursorRect" in line
                    or "member=SetCursorLocation" in line
                    or "member=set_cursor_location" in line
                ):
                    print(f"[Q704-FOCUS] DBUS CURSOR {line}", flush=True)
                    emit_visibility(True, None, "dbus-cursor")

            try:
                proc.wait(timeout=1)
            except Exception:
                pass

        except Exception as ex:
            print(f"[Q704-FOCUS] DBus monitor error: {ex}", flush=True)
            time.sleep(1.0)
        finally:
            if proc is not None and proc.poll() is None:
                try:
                    proc.terminate()
                except Exception:
                    pass

def polling_loop():
    print("[Q704-FOCUS] polling started 200ms", flush=True)

    while parent_alive():
        try:
            obj = currently_focused_object()
            if obj is not None and is_editable(obj):
                emit_visibility(True, obj, "poll")
            else:
                emit_visibility(False, obj, "poll")
        except Exception as ex:
            print(f"[Q704-FOCUS] polling error: {ex}", flush=True)

        time.sleep(0.20)

    os._exit(0)

print(
    f"[Q704-FOCUS] helper started pid={os.getpid()} parent={PARENT_PID}",
    flush=True,
)

try:
    _desktop_probe = pyatspi.Registry.getDesktop(0)
    print(
        f"[Q704-FOCUS] AT-SPI connected, desktop children={_desktop_probe.childCount}",
        flush=True,
    )
except Exception as ex:
    print(f"[Q704-FOCUS] AT-SPI probe failed: {ex}", flush=True)
    raise

# Fcitx5/IBus focus is the most direct signal that an input field is active.
threading.Thread(
    target=dbus_input_monitor_loop,
    name="q704-dbus-input-monitor",
    daemon=True,
).start()

# AT-SPI polling remains the fallback for Wayland/native apps.
threading.Thread(
    target=polling_loop,
    name="q704-focus-poll",
    daemon=True,
).start()

# Event path: instant response when an app exposes proper AT-SPI events.
pyatspi.Registry.registerEventListener(
    on_focus,
    "object:state-changed:focused",
)
pyatspi.Registry.registerEventListener(
    on_caret,
    "object:text-caret-moved",
)
pyatspi.Registry.registerEventListener(
    on_editable,
    "object:state-changed:editable",
)
pyatspi.Registry.registerEventListener(
    on_text_insert,
    "object:text-changed:insert",
)

try:
    pyatspi.Registry.start()
except KeyboardInterrupt:
    pass
