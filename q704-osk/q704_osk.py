#!/usr/bin/env python3
import os
import subprocess
from pathlib import Path

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
try:
    gi.require_version("GdkX11", "3.0")
    from gi.repository import GdkX11
except Exception:
    GdkX11 = None

from gi.repository import Gtk, Gdk, GLib
from evdev import UInput, ecodes as e

try:
    import pyatspi
except Exception:
    pyatspi = None

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

EDITABLE_ROLES = set()
if pyatspi:
    for role_name in ("ROLE_ENTRY", "ROLE_PASSWORD_TEXT", "ROLE_TEXT", "ROLE_PARAGRAPH", "ROLE_DOCUMENT_TEXT"):
        role = getattr(pyatspi, role_name, None)
        if role is not None:
            EDITABLE_ROLES.add(role)

class InputBackend:
    def __init__(self):
        caps = {e.EV_KEY: sorted(set(KEYS.values()) | {e.KEY_LEFTMETA, e.KEY_SPACE})}
        self.ui = UInput(
            caps,
            name="Q704 Zorin Virtual Keyboard",
            vendor=0x10cf,
            product=0x0704,
            version=2,
        )

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
            path = uri[7:]
            if os.path.exists(path):
                return path
    except Exception:
        pass

    for root in ("/usr/share/backgrounds", "/usr/share/zorin-os"):
        root_path = Path(root)
        if root_path.exists():
            for pattern in ("*zorin*.jpg", "*zorin*.png", "*.jpg", "*.png"):
                found = next(iter(root_path.rglob(pattern)), None)
                if found:
                    return str(found)
    return None

def css_for(wallpaper):
    bg = ""
    if wallpaper:
        esc = wallpaper.replace("\\", "\\\\").replace('"', '\\"')
        bg = (
            f'background-image: url("file://{esc}"); '
            'background-size: cover; background-position: center;'
        )
    return f"""
    window.q704-keyboard {{
        {bg}
        background-color: rgba(20, 24, 34, 0.98);
    }}
    .keyboard-panel {{
        background-color: rgba(18, 22, 31, 0.87);
        border-radius: 22px 22px 0 0;
        padding: 12px 14px 14px 14px;
    }}
    button.key {{
        min-height: 52px;
        min-width: 42px;
        margin: 3px;
        border-radius: 12px;
        border: 1px solid rgba(255,255,255,0.13);
        background-image: none;
        background-color: rgba(42, 48, 62, 0.94);
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
    button.enter-key {{
        background-color: rgba(45, 126, 247, 0.92);
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
        self.enter_buttons = []
        self.hide_timer = None
        self.keyboard_height = 0

        provider = Gtk.CssProvider()
        provider.load_from_data(css_for(current_wallpaper()).encode())
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
        self.keyboard.set_type_hint(Gdk.WindowTypeHint.DOCK)
        self.keyboard.set_gravity(Gdk.Gravity.SOUTH_WEST)
        self.keyboard.connect("delete-event", self.on_hide)
        self.keyboard.connect("map-event", self.on_keyboard_mapped)

        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=1)
        panel.get_style_context().add_class("keyboard-panel")
        self.keyboard.add(panel)

        for row in ROWS:
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=1)
            box.set_homogeneous(False)
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

        self.handle = Gtk.Window(type=Gtk.WindowType.TOPLEVEL)
        self.handle.set_decorated(False)
        self.handle.set_resizable(False)
        self.handle.set_keep_above(True)
        self.handle.set_skip_taskbar_hint(True)
        self.handle.set_skip_pager_hint(True)
        self.handle.set_accept_focus(False)
        self.handle.set_focus_on_map(False)
        self.handle.set_type_hint(Gdk.WindowTypeHint.UTILITY)

        handle_button = Gtk.Button(label="⌨")
        handle_button.set_can_focus(False)
        handle_button.set_focus_on_click(False)
        handle_button.get_style_context().add_class("handle")
        handle_button.set_tooltip_text("Mở / ẩn bàn phím Q704")
        handle_button.connect("clicked", self.toggle)
        self.handle.add(handle_button)

        self.handle.show_all()
        GLib.idle_add(self.position_handle)
        GLib.timeout_add(1200, self.keep_positioned)
        GLib.idle_add(self.setup_accessibility_watcher)
        GLib.timeout_add(700, self.poll_focused_editable)

    def show_fatal(self, ex):
        dialog = Gtk.MessageDialog(
            transient_for=None,
            flags=0,
            message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.CLOSE,
            text="Q704 Zorin Keyboard chưa truy cập được /dev/uinput",
        )
        dialog.format_secondary_text(
            f"{ex}\n\nHãy chạy:\n"
            "sudo modprobe uinput\n"
            "sudo udevadm control --reload-rules\n"
            "sudo udevadm trigger /dev/uinput\n"
            "sau đó đăng xuất/đăng nhập lại nếu cần."
        )
        dialog.run()
        dialog.destroy()
        GLib.idle_add(Gtk.main_quit)

    def monitor_info(self):
        screen = Gdk.Screen.get_default()
        monitor = screen.get_primary_monitor()
        if monitor < 0:
            monitor = 0
        geo = screen.get_monitor_geometry(monitor)
        try:
            work = screen.get_monitor_workarea(monitor)
        except Exception:
            work = geo
        return geo, work

    def screen_geometry(self):
        return self.monitor_info()[0]

    def workarea_geometry(self):
        return self.monitor_info()[1]

    def keyboard_dimensions(self):
        _geo, work = self.monitor_info()
        width = work.width
        height = min(380, max(300, int(work.height * 0.40)))
        return width, height

    def position_handle(self):
        _geo, work = self.monitor_info()
        self.handle.resize(60, 60)
        self.handle.move(work.x + work.width - 78, work.y + work.height - 72)
        return False

    def keep_positioned(self):
        if self.handle.get_visible():
            self.position_handle()
        if self.keyboard.get_visible():
            self.position_keyboard()
        return True

    def position_keyboard(self):
        _geo, work = self.monitor_info()
        width, height = self.keyboard_dimensions()
        self.keyboard_height = height
        self.keyboard.resize(width, height)
        # Anchor above the Zorin panel/taskbar, never underneath it.
        self.keyboard.move(work.x, work.y + work.height - height)

    def on_keyboard_mapped(self, *_):
        self.position_keyboard()
        GLib.timeout_add(80, self.apply_bottom_strut)
        return False

    def x11_window_id(self):
        if GdkX11 is None:
            return None
        gdk_window = self.keyboard.get_window()
        if not gdk_window:
            return None
        try:
            return GdkX11.X11Window.get_xid(gdk_window)
        except Exception:
            try:
                return gdk_window.get_xid()
            except Exception:
                return None

    def apply_bottom_strut(self):
        xid = self.x11_window_id()
        if not xid or not self.keyboard.get_visible():
            return False

        geo, work = self.monitor_info()
        height = self.keyboard_height or self.keyboard_dimensions()[1]
        keyboard_top = work.y + work.height - height
        # Bottom strut is measured from the physical monitor bottom. Include
        # the Zorin panel height so maximized apps stay above panel + keyboard.
        bottom_reserve = max(height, (geo.y + geo.height) - keyboard_top)
        start_x = max(0, work.x)
        end_x = max(start_x, work.x + work.width - 1)

        try:
            subprocess.run([
                "xprop", "-id", str(xid),
                "-f", "_NET_WM_STRUT", "32c",
                "-set", "_NET_WM_STRUT",
                f"0, 0, 0, {bottom_reserve}",
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            subprocess.run([
                "xprop", "-id", str(xid),
                "-f", "_NET_WM_STRUT_PARTIAL", "32c",
                "-set", "_NET_WM_STRUT_PARTIAL",
                f"0, 0, 0, {bottom_reserve}, 0, 0, 0, 0, 0, 0, {start_x}, {end_x}",
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        except Exception:
            pass
        return False

    def clear_bottom_strut(self):
        xid = self.x11_window_id()
        if not xid:
            return
        try:
            subprocess.run(
                ["xprop", "-id", str(xid), "-remove", "_NET_WM_STRUT"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
            )
            subprocess.run(
                ["xprop", "-id", str(xid), "-remove", "_NET_WM_STRUT_PARTIAL"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
            )
        except Exception:
            pass

    def show_keyboard(self):
        if self.hide_timer:
            try:
                GLib.source_remove(self.hide_timer)
            except Exception:
                pass
            self.hide_timer = None
        if not self.keyboard.get_visible():
            self.keyboard.show_all()
        self.position_keyboard()
        GLib.timeout_add(80, self.apply_bottom_strut)
        return False

    def hide_keyboard(self):
        if self.keyboard.get_visible():
            self.clear_bottom_strut()
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

    def setup_accessibility_watcher(self):
        if pyatspi is None:
            return False
        try:
            pyatspi.Registry.registerEventListener(
                self.on_focus_event,
                "object:state-changed:focused",
            )
            pyatspi.Registry.registerEventListener(
                self.on_text_activity,
                "object:text-caret-moved",
            )
            pyatspi.Registry.registerEventListener(
                self.on_text_activity,
                "object:state-changed:editable",
            )
            pyatspi.Registry.registerEventListener(
                self.on_window_activate,
                "window:activate",
            )
        except Exception:
            pass
        return False

    def on_text_activity(self, event):
        try:
            source = event.source
        except Exception:
            return
        if self.accessible_is_editable(source):
            self.update_enter_mode(source)
            GLib.idle_add(self.show_keyboard)

    def on_window_activate(self, _event):
        GLib.timeout_add(120, self.poll_focused_editable)

    def find_focused_descendant(self, root, max_nodes=3000):
        if pyatspi is None or root is None:
            return None
        stack = [root]
        seen = 0
        while stack and seen < max_nodes:
            obj = stack.pop()
            seen += 1
            try:
                state = obj.getState()
                if state.contains(pyatspi.STATE_FOCUSED):
                    return obj
                count = obj.childCount
                # Reverse so the first children remain first in DFS.
                for i in range(count - 1, -1, -1):
                    child = obj.getChildAtIndex(i)
                    if child is not None:
                        stack.append(child)
            except Exception:
                continue
        return None

    def poll_focused_editable(self):
        if pyatspi is None:
            return True
        try:
            desktop = pyatspi.Registry.getDesktop(0)
            for ai in range(desktop.childCount):
                app = desktop.getChildAtIndex(ai)
                if app is None:
                    continue
                for wi in range(app.childCount):
                    win = app.getChildAtIndex(wi)
                    if win is None:
                        continue
                    try:
                        state = win.getState()
                        if not state.contains(pyatspi.STATE_ACTIVE):
                            continue
                    except Exception:
                        continue
                    focused = self.find_focused_descendant(win)
                    if focused is not None and self.accessible_is_editable(focused):
                        self.update_enter_mode(focused)
                        self.show_keyboard()
                        return True
        except Exception:
            pass
        return True

    def accessible_is_editable(self, obj):
        if obj is None or pyatspi is None:
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
                getattr(pyatspi, "ROLE_ENTRY", -1),
                getattr(pyatspi, "ROLE_PASSWORD_TEXT", -1),
            ):
                return True
        except Exception:
            pass
        return False

    def update_enter_mode(self, obj):
        label = "↵"
        text = ""
        try:
            text = " ".join([
                str(getattr(obj, "name", "") or ""),
                str(getattr(obj, "description", "") or ""),
            ]).lower()
        except Exception:
            pass

        search_words = ("search", "tìm", "find", "address", "url")
        send_words = ("message", "tin nhắn", "chat", "comment", "reply", "gửi", "send")

        if any(word in text for word in search_words):
            label = "Tìm ↵"
        elif any(word in text for word in send_words):
            label = "Gửi ↵"

        for button in self.enter_buttons:
            button.set_label(label)

    def on_focus_event(self, event):
        try:
            focused = bool(event.detail1)
            source = event.source
        except Exception:
            return

        if focused and self.accessible_is_editable(source):
            self.update_enter_mode(source)
            GLib.idle_add(self.show_keyboard)
            return

        if focused and not self.accessible_is_editable(source):
            if self.hide_timer:
                try:
                    GLib.source_remove(self.hide_timer)
                except Exception:
                    pass
            self.hide_timer = GLib.timeout_add(220, self.hide_if_focus_still_not_editable)

    def hide_if_focus_still_not_editable(self):
        self.hide_timer = None
        self.hide_keyboard()
        return False

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

        self.backend.tap(code, shift=self.shift, ctrl=self.ctrl, alt=self.alt)

        if self.shift:
            self.shift = False
            self.update_shift_labels()
        self.ctrl = False
        self.alt = False

        if action == "Enter":
            GLib.timeout_add(120, self.hide_keyboard)

    def run(self):
        Gtk.main()

if __name__ == "__main__":
    app = KeyboardApp()
    if hasattr(app, "backend"):
        app.run()
