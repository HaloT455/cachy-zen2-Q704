#!/usr/bin/env python3
import os
import signal
import subprocess

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, Gdk, GLib

try:
    gi.require_version("AyatanaAppIndicator3", "0.1")
    from gi.repository import AyatanaAppIndicator3
except Exception:
    AyatanaAppIndicator3 = None

from evdev import UInput, ecodes as e

APP_NAME = "Q704 Zorin Keyboard - V1 Core"

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
    "Right": e.KEY_RIGHT, "Up": e.KEY_UP, "Down": e.KEY_DOWN, "Del": e.KEY_DELETE,
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

def css():
    return """
    window.q704-keyboard {
        background-image: none;
        background-color: rgba(62, 24, 45, 0.98);
    }
    .keyboard-panel {
        background-color: rgba(70, 28, 51, 0.96);
        border-radius: 20px 20px 0 0;
        padding: 12px;
    }
    button.key {
        min-height: 52px;
        min-width: 42px;
        margin: 3px;
        border-radius: 12px;
        border: 1px solid rgba(255,255,255,0.14);
        background-image: none;
        background-color: rgba(92, 46, 67, 0.96);
        color: #ffffff;
        font-weight: 600;
        font-size: 16px;
    }
    button.key:hover {
        background-color: rgba(255, 111, 168, 0.92);
    }
    button.key:active, button.shift-on {
        background-color: #ff5fa2;
    }
    button.enter-key {
        background-color: #ff4f9a;
    }
    """

class InputBackend:
    def __init__(self):
        caps = {e.EV_KEY: sorted(set(KEYS.values()) | {e.KEY_LEFTMETA, e.KEY_SPACE})}
        self.ui = UInput(
            caps,
            name="Q704 Zorin Virtual Keyboard",
            vendor=0x10cf,
            product=0x0704,
            version=10,
        )

    def tap(self, code, shift=False, ctrl=False, alt=False):
        mods = []
        if shift:
            mods.append(e.KEY_LEFTSHIFT)
        if ctrl:
            mods.append(e.KEY_LEFTCTRL)
        if alt:
            mods.append(e.KEY_LEFTALT)

        for mod in mods:
            self.ui.write(e.EV_KEY, mod, 1)

        self.ui.write(e.EV_KEY, code, 1)
        self.ui.syn()
        self.ui.write(e.EV_KEY, code, 0)

        for mod in reversed(mods):
            self.ui.write(e.EV_KEY, mod, 0)

        self.ui.syn()

    def close(self):
        self.ui.close()

class KeyboardApp:
    def __init__(self):
        self.backend = InputBackend()
        self.shift = False
        self.ctrl = False
        self.alt = False
        self.key_buttons = []
        self.enter_buttons = []
        self.focus_helper = None

        provider = Gtk.CssProvider()
        provider.load_from_data(css().encode())
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(),
            provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
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
        # V1 core behavior: utility window, not GNOME DOCK. This avoids the
        # panel/WM moving the keyboard around.
        self.keyboard.set_type_hint(Gdk.WindowTypeHint.UTILITY)
        self.keyboard.connect("delete-event", self.on_hide)

        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=1)
        panel.get_style_context().add_class("keyboard-panel")
        self.keyboard.add(panel)

        for row in ROWS:
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=1)
            panel.pack_start(box, True, True, 0)
            for label, action, weight in row:
                button = Gtk.Button(label=label)
                button.set_can_focus(False)
                button.set_focus_on_click(False)
                button.get_style_context().add_class("key")
                if action == "Enter":
                    button.get_style_context().add_class("enter-key")
                    self.enter_buttons.append(button)
                button.connect("clicked", self.on_key, action)
                box.pack_start(button, True, True, 0)
                button.set_size_request(max(42, int(54 * weight)), 54)
                self.key_buttons.append((button, label, action))

        self.indicator = None
        self.setup_indicator()

        # Focus watcher is intentionally isolated from the V1 keyboard core.
        signal.signal(signal.SIGUSR1, self._sig_show)
        signal.signal(signal.SIGUSR2, self._sig_hide)
        self.start_focus_helper()
        GLib.timeout_add(3000, self.ensure_focus_helper)

    def setup_indicator(self):
        if AyatanaAppIndicator3 is None:
            return

        self.indicator = AyatanaAppIndicator3.Indicator.new(
            "q704-zorin-keyboard",
            "q704-keyboard-pink",
            AyatanaAppIndicator3.IndicatorCategory.APPLICATION_STATUS,
        )
        try:
            self.indicator.set_icon_theme_path("/usr/share/icons/hicolor/scalable/apps")
        except Exception:
            pass

        self.indicator.set_status(AyatanaAppIndicator3.IndicatorStatus.ACTIVE)
        self.indicator.set_title("Q704 Keyboard")

        menu = Gtk.Menu()

        toggle_item = Gtk.MenuItem(label="Mở / ẩn bàn phím")
        toggle_item.connect("activate", self.toggle)
        menu.append(toggle_item)

        hide_item = Gtk.MenuItem(label="Ẩn bàn phím")
        hide_item.connect("activate", lambda *_: self.hide_keyboard())
        menu.append(hide_item)

        menu.append(Gtk.SeparatorMenuItem())

        quit_item = Gtk.MenuItem(label="Thoát")
        quit_item.connect("activate", self.quit_app)
        menu.append(quit_item)

        menu.show_all()
        self.indicator.set_menu(menu)

    def monitor_workarea(self):
        screen = Gdk.Screen.get_default()
        mon = screen.get_primary_monitor()
        if mon < 0:
            mon = 0

        try:
            work = screen.get_monitor_workarea(mon)
        except Exception:
            work = screen.get_monitor_geometry(mon)

        return work

    def position_keyboard(self):
        work = self.monitor_workarea()
        width = work.width
        height = min(360, max(300, int(work.height * 0.38)))
        x = work.x
        y = work.y + work.height - height
        self.keyboard.resize(width, height)
        self.keyboard.move(x, y)

    def show_keyboard(self):
        self.position_keyboard()
        if not self.keyboard.get_visible():
            self.keyboard.show_all()
        # Re-apply once after map. Keeps V1's simple positioning without
        # STRUT/DOCK/wmctrl jumping.
        GLib.timeout_add(80, self._reposition_once)
        return False

    def _reposition_once(self):
        if self.keyboard.get_visible():
            self.position_keyboard()
        return False

    def hide_keyboard(self):
        self.keyboard.hide()
        return False

    def toggle(self, *_):
        if self.keyboard.get_visible():
            self.hide_keyboard()
        else:
            self.show_keyboard()

    def on_hide(self, *_):
        self.hide_keyboard()
        return True

    def _sig_show(self, *_):
        GLib.idle_add(self.show_keyboard)

    def _sig_hide(self, *_):
        GLib.idle_add(self.hide_keyboard)

    def start_focus_helper(self):
        if self.focus_helper and self.focus_helper.poll() is None:
            return

        helper = "/usr/lib/q704-zorin-keyboard/q704_focusd.py"
        if not os.path.exists(helper):
            return

        env = os.environ.copy()
        env["NO_AT_BRIDGE"] = "0"
        env["QT_ACCESSIBILITY"] = "1"

        log = open("/tmp/q704-focusd.log", "a", buffering=1)
        self.focus_helper = subprocess.Popen(
            ["/usr/bin/python3", helper, str(os.getpid())],
            stdout=log,
            stderr=log,
            env=env,
        )

    def ensure_focus_helper(self):
        self.start_focus_helper()
        return True

    def update_shift_labels(self):
        for button, original, action in self.key_buttons:
            if action == "Shift":
                ctx = button.get_style_context()
                if self.shift:
                    ctx.add_class("shift-on")
                else:
                    ctx.remove_class("shift-on")
            elif len(action) == 1 and action.isalpha():
                button.set_label(original.upper() if self.shift else original.lower())
            elif action in SHIFT_LABELS:
                button.set_label(SHIFT_LABELS[action] if self.shift else original)

    def on_key(self, _button, action):
        if action == "Hide":
            self.hide_keyboard()
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
            self.backend.tap(e.KEY_SPACE, ctrl=True)
            return

        code = KEYS.get(action)
        if code is None:
            return

        self.backend.tap(
            code,
            shift=self.shift,
            ctrl=self.ctrl,
            alt=self.alt,
        )

        if self.shift:
            self.shift = False
            self.update_shift_labels()

        self.ctrl = False
        self.alt = False

        # Gboard-like requirement: Enter / Search / Send finishes input and
        # hides the keyboard. The actual Enter key event is sent first.
        if action == "Enter":
            GLib.timeout_add(100, self.hide_keyboard)

    def quit_app(self, *_):
        try:
            if self.focus_helper and self.focus_helper.poll() is None:
                self.focus_helper.terminate()
        except Exception:
            pass
        try:
            self.backend.close()
        except Exception:
            pass
        Gtk.main_quit()

    def run(self):
        Gtk.main()

if __name__ == "__main__":
    try:
        app = KeyboardApp()
        app.run()
    except Exception as ex:
        print(f"[Q704] fatal: {ex}", flush=True)
        raise
