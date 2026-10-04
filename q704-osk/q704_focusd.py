#!/usr/bin/env python3
import os
import signal
import sys
import time

import pyatspi

if len(sys.argv) != 2:
    raise SystemExit("usage: q704_focusd.py <keyboard-pid>")

PARENT_PID = int(sys.argv[1])
last_editable = None

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

def is_editable(obj):
    if obj is None:
        return False

    try:
        state = obj.getState()
        if state.contains(pyatspi.STATE_EDITABLE):
            return True
    except Exception:
        pass

    try:
        role = obj.getRole()
        if role in (
            getattr(pyatspi, "ROLE_ENTRY", -999),
            getattr(pyatspi, "ROLE_PASSWORD_TEXT", -998),
        ):
            return True
    except Exception:
        pass

    return False

def describe(obj):
    try:
        role = obj.getRoleName()
    except Exception:
        role = "?"
    try:
        name = obj.name or ""
    except Exception:
        name = ""
    return f"role={role} name={name!r}"

def on_focus(event):
    global last_editable

    try:
        focused = bool(event.detail1)
        obj = event.source
    except Exception:
        return

    editable = is_editable(obj)

    if focused and editable:
        last_editable = obj
        print(f"[Q704-FOCUS] SHOW {describe(obj)}", flush=True)
        send(signal.SIGUSR1)
        return

    if not focused and editable:
        print(f"[Q704-FOCUS] HIDE {describe(obj)}", flush=True)
        last_editable = None
        send(signal.SIGUSR2)

def on_caret(event):
    obj = getattr(event, "source", None)
    if is_editable(obj):
        print(f"[Q704-FOCUS] CARET {describe(obj)}", flush=True)
        send(signal.SIGUSR1)

def on_editable(event):
    obj = getattr(event, "source", None)
    try:
        enabled = bool(event.detail1)
    except Exception:
        enabled = False

    if enabled and is_editable(obj):
        print(f"[Q704-FOCUS] EDITABLE {describe(obj)}", flush=True)
        send(signal.SIGUSR1)

print(f"[Q704-FOCUS] helper started pid={os.getpid()} parent={PARENT_PID}", flush=True)

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

try:
    pyatspi.Registry.start()
except KeyboardInterrupt:
    pass
