#!/usr/bin/env python3
import os
import sys
import subprocess
from pathlib import Path

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, Gdk, GLib

from evdev import UInput, ecodes as e

APP_NAME = "Q704 Zorin Keyboard"

KEYS = {
    "Esc": e.KEY_ESC, "1": e.KEY_1, "2": e.KEY_2, "3": e.KEY_3, "4": e.KEY_4,
    "5": e.KEY_5, "6": e.KEY_6, "7": e.KEY_7, "8": e.KEY_8, "9": e.KEY_9,
    "0": e.KEY_0, "-": e.KEY_MINUS, "=": e.KEY_EQUAL, "Back": e.KEY_BACKSPACE,
    "Tab": e.KEY_TAB, "q": e.KEY_Q, "w": e.KEY_W, "e": e.KEY_E, "r": e.KEY_R,
    "t": e.KEY_T, "y": e.KEY_Y, "u": e.KEY_U, "i": e.KEY_I, "o": e.KEY_O,
    "p": e.KEY_P, "[": e.KEY_LEFTBRACE, "]": e.KEY_RIGHTBRACE,
    "Enter": e.KEY_ENTER, "Caps": e.KEY_CAPSLOCK, "a": e.KEY_A, "s": e.KEY_S,
    "d": e.KEY_D, "f": e.KEY_F, "g": e.KEY_G, "h": e.KEY_H, "j": e.KEY_J,
    "k": e.KEY_K, "l": e.KEY_L, ";": e.KEY_SEMICOLON, "'": e.KEY_APOSTROPHE,
    "Shift": e.KEY_LEFTSHIFT, "\\": e.KEY_BACKSLASH, "z": e.KEY_Z, "x": e.KEY_X,
    "c": e.KEY_C, "v": e.KEY_V, "b": e.KEY_B, "n": e.KEY_N, "m": e.KEY_M,
    ",": e.KEY_COMMA, ".": e.KEY_DOT, "/": e.KEY_SLASH, "Ctrl": e.KEY_LEFTCTRL,
    "Alt": e.KEY_LEFTALT, "Space": e.KEY_SPACE, "Left": e.KEY_LEFT,
    "Right": e.KEY_RIGHT, "Up": e.KEY_UP, "Down": e.KEY_DOWN,
    "Del": e.KEY_DELETE,
}

SHIFT_LABELS = {
    "1":"!", "2":"@", "3":"#", "4":"$", "5":"%", "6":"^", "7":"&", "8":"*",
    "9":"(", "0":")", "-":"_", "=":"+", "[":"{", "]":"}", ";":":",
    "'":'"', "\\":"|", ",":"<", ".":">", "/":"?"
}

ROWS = [
    [("Esc","Esc",1.0), ("1","1",1.0), ("2","2",1.0), ("3","3",1.0), ("4","4",1.0),
     ("5","5",1.0), ("6","6",1.0), ("7","7",1.0), ("8","8",1.0), ("9","9",1.0),
     ("0","0",1.0), ("-","-",1.0), ("=","=",1.0), ("⌫","Back",1.7)],
    [("Tab","Tab",1.45), ("q","q",1.0), ("w","w",1.0), ("e","e",1.0), ("r","r",1.0),
     ("t","t",1.0), ("y","y",1.0), ("u","u",1.0), ("i","i",1.0), ("o","o",1.0),
     ("p","p",1.0), ("[","[",1.0), ("]","]",1.0), ("↵","Enter",1.45)],
    [("Caps","Caps",1.65), ("a","a",1.0), ("s","s",1.0), ("d","d",1.0), ("f","f",1.0),
     ("g","g",1.0), ("h","h",1.0), ("j","j",1.0), ("k","k",1.0), ("l","l",1.0),
     (";",";",1.0), ("'","'",1.0), ("Enter","Enter",2.0)],
    [("⇧","Shift",2.0), ("z","z",1.0), ("x","x",1.0), ("c","c",1.0), ("v","v",1.0),
     ("b","b",1.0), ("n","n",1.0), ("m","m",1.0), (",",",",1.0), (".",".",1.0),
     ("/","/",1.0), ("⇧","Shift",2.0)],
    [("Ctrl","Ctrl",1.2), ("Alt","Alt",1.2), ("VI/EN","IME",1.3), ("Space","Space",5.4),
     ("←","Left",1.0), ("↓","Down",1.0), ("↑","Up",1.0), ("→","Right",1.0),
     ("Del","Del",1.2), ("Ẩn","Hide",1.2)]
]

class InputBackend:
    def __init__(self):
        caps = {e.EV_KEY: sorted(set(KEYS.values()) | {e.KEY_LEFTMETA, e.KEY_SPACE})}
        try:
            self.ui = UInput(caps, name="Q704 Zorin Virtual Keyboard",
                             vendor=0x10cf, product=0x0704, version=1)
        except PermissionError:
            self.ui = None
            raise
        except OSError:
            self.ui = None
            raise

    def tap(self, code, shift=False, ctrl=False, alt=False):
        mods = []
        if shift:
            mods.append(e.KEY_LEFTSHIFT)
        if ctrl:
            mods.append(e.KEY_LEFTCTRL)
        if alt:
            mods.append(e.KEY_LEFTALT)
        for m in mods:
            self.ui.write(e.EV_KEY, m, 1)
        self.ui.write(e.EV_KEY, code, 1)
        self.ui.syn()
        self.ui.write(e.EV_KEY, code, 0)
        for m in reversed(mods):
            self.ui.write(e.EV_KEY, m, 0)
        self.ui.syn()

    def close(self):
        if self.ui:
            self.ui.close()

def current_wallpaper():
    try:
        uri = subprocess.check_output(
            ["gsettings", "get", "org.gnome.desktop.background", "picture-uri-dark"],
            text=True, stderr=subprocess.DEVNULL
        ).strip().strip("'")
        if not uri or uri == "''":
            uri = subprocess.check_output(
                ["gsettings", "get", "org.gnome.desktop.background", "picture-uri"],
                text=True, stderr=subprocess.DEVNULL
            ).strip().strip("'")
        if uri.startswith("file://"):
            p = uri[7:]
            if os.path.exists(p):
                return p
    except Exception:
        pass
    for root in ("/usr/share/backgrounds", "/usr/share/zorin-os"):
        p = Path(root)
        if p.exists():
            for pat in ("*zorin*.jpg", "*zorin*.png", "*.jpg", "*.png"):
                found = next(iter(p.rglob(pat)), None)
                if found:
                    return str(found)
    return None

def css_for(wallpaper):
    bg = ""
    if wallpaper:
        esc = wallpaper.replace("\\", "\\\\").replace('"', '\\"')
        bg = f'background-image: url("file://{esc}"); background-size: cover; background-position: center;'
    return f"""
    window.q704-keyboard {{
        {bg}
        background-color: rgba(20, 24, 34, 0.97);
    }}
    .keyboard-panel {{
        background-color: rgba(18, 22, 31, 0.84);
        border-radius: 22px;
        padding: 14px;
    }}
    button.key {{
        min-height: 52px;
        min-width: 42px;
        margin: 3px;
        border-radius: 12px;
        border: 1px solid rgba(255,255,255,0.13);
        background-image: none;
        background-color: rgba(42, 48, 62, 0.92);
        color: #ffffff;
        font-weight: 600;
        font-size: 16px;
        box-shadow: inset 0 1px rgba(255,255,255,0.06);
    }}
    button.key:hover {{
        background-color: rgba(63, 142, 255, 0.82);
    }}
    button.key:active, button.shift-on {{
        background-color: #2d7ef7;
    }}
    button.handle {{
        min-width: 56px;
        min-height: 56px;
        border-radius: 18px;
        background-image: none;
        background-color: rgba(28, 34, 46, 0.94);
        color: white;
        font-size: 25px;
        border: 1px solid rgba(255,255,255,0.18);
    }}
    """

class KeyboardApp:
    def __init__(self):
        try:
            self.backend = InputBackend()
        except Exception as ex:
            self.show_fatal(ex)
            return

        self.shift = False
        self.ctrl = False
        self.alt = False
        self.key_buttons = []

        provider = Gtk.CssProvider()
        provider.load_from_data(css_for(current_wallpaper()).encode())
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        self.keyboard = Gtk.Window(type=Gtk.WindowType.TOPLEVEL)
        self.keyboard.set_name("q704-keyboard")
        self.keyboard.get_style_context().add_class("q704-keyboard")
        self.keyboard.set_title(APP_NAME)
        self.keyboard.set_decorated(False)
        self.keyboard.set_resizable(False)
        self.keyboard.set_keep_above(True)
        self.keyboard.set_skip_taskbar_hint(True)
        self.keyboard.set_skip_pager_hint(True)
        self.keyboard.set_accept_focus(False)
        self.keyboard.set_focus_on_map(False)
        self.keyboard.set_type_hint(Gdk.WindowTypeHint.DOCK)
        self.keyboard.connect("delete-event", self.on_hide)

        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=1)
        panel.get_style_context().add_class("keyboard-panel")
        self.keyboard.add(panel)

        for row in ROWS:
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=1)
            box.set_homogeneous(False)
            panel.pack_start(box, True, True, 0)
            for label, action, weight in row:
                b = Gtk.Button(label=label)
                b.set_can_focus(False)
                b.set_focus_on_click(False)
                b.get_style_context().add_class("key")
                b.connect("clicked", self.on_key, action)
                box.pack_start(b, True, True, 0)
                b.set_size_request(max(42, int(54 * weight)), 54)
                self.key_buttons.append((b, label, action))

        self.handle = Gtk.Window(type=Gtk.WindowType.TOPLEVEL)
        self.handle.set_decorated(False)
        self.handle.set_resizable(False)
        self.handle.set_keep_above(True)
        self.handle.set_skip_taskbar_hint(True)
        self.handle.set_skip_pager_hint(True)
        self.handle.set_accept_focus(False)
        self.handle.set_focus_on_map(False)
        self.handle.set_type_hint(Gdk.WindowTypeHint.UTILITY)
        hb = Gtk.Button(label="⌨")
        hb.set_can_focus(False)
        hb.set_focus_on_click(False)
        hb.get_style_context().add_class("handle")
        hb.set_tooltip_text("Mở / ẩn bàn phím Q704")
        hb.connect("clicked", self.toggle)
        self.handle.add(hb)

        self.handle.show_all()
        GLib.idle_add(self.position_handle)
        GLib.timeout_add(1500, self.keep_positioned)

    def show_fatal(self, ex):
        d = Gtk.MessageDialog(
            transient_for=None, flags=0, message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.CLOSE, text="Q704 Zorin Keyboard chưa truy cập được /dev/uinput"
        )
        d.format_secondary_text(
            f"{ex}\n\nHãy chạy:\n"
            "sudo modprobe uinput\n"
            "sudo udevadm control --reload-rules\n"
            "sudo udevadm trigger /dev/uinput\n"
            "sau đó đăng xuất/đăng nhập lại nếu cần."
        )
        d.run()
        d.destroy()
        GLib.idle_add(Gtk.main_quit)

    def screen_geometry(self):
        scr = Gdk.Screen.get_default()
        mon = scr.get_primary_monitor()
        if mon < 0:
            mon = 0
        return scr.get_monitor_geometry(mon)

    def position_handle(self):
        geo = self.screen_geometry()
        self.handle.resize(60, 60)
        self.handle.move(geo.x + geo.width - 78, geo.y + geo.height - 82)
        return False

    def keep_positioned(self):
        if self.handle.get_visible():
            self.position_handle()
        return True

    def position_keyboard(self):
        geo = self.screen_geometry()
        width = max(800, int(geo.width * 0.96))
        height = min(380, max(300, int(geo.height * 0.42)))
        self.keyboard.resize(width, height)
        self.keyboard.move(geo.x + (geo.width - width)//2, geo.y + geo.height - height - 12)

    def toggle(self, *_):
        if self.keyboard.get_visible():
            self.keyboard.hide()
        else:
            self.keyboard.show_all()
            self.position_keyboard()

    def on_hide(self, *_):
        self.keyboard.hide()
        return True

    def update_shift_labels(self):
        for b, original, action in self.key_buttons:
            if action in ("Shift",):
                ctx = b.get_style_context()
                if self.shift:
                    ctx.add_class("shift-on")
                else:
                    ctx.remove_class("shift-on")
            elif len(action) == 1 and action.isalpha():
                b.set_label(original.upper() if self.shift else original.lower())
            elif action in SHIFT_LABELS:
                b.set_label(SHIFT_LABELS[action] if self.shift else original)

    def on_key(self, _button, action):
        if action == "Hide":
            self.keyboard.hide()
            return
        if action == "Shift":
            self.shift = not self.shift
            self.update_shift_labels()
            return
        if action == "Ctrl":
            self.ctrl = not self.ctrl
            return
        if action == "Alt":
            self.alt = not self.alt
            return
        if action == "IME":
            # Common Fcitx/IBus toggle: Ctrl+Space.
            self.backend.tap(e.KEY_SPACE, ctrl=True)
            return

        code = KEYS.get(action)
        if code is None:
            return

        self.backend.tap(code, shift=self.shift, ctrl=self.ctrl, alt=self.alt)
        if self.shift:
            self.shift = False
            self.update_shift_labels()
        self.ctrl = False
        self.alt = False

    def run(self):
        Gtk.main()

if __name__ == "__main__":
    app = KeyboardApp()
    if hasattr(app, "backend"):
        app.run()
