package gg.kite.core;

/** A configurable key plus hold/toggle behaviour. Owned by a mod; the mod forwards key events to it. */
public final class Hotkey {
    public enum Mode { HOLD, TOGGLE }

    public final Setting.Key key;
    public final Setting.Choice<Mode> mode;
    private boolean active;

    Hotkey(Setting.Key key, Setting.Choice<Mode> mode) {
        this.key = key;
        this.mode = mode;
    }

    /** Feed a key event. Returns true if this hotkey's state changed. */
    public boolean onKey(int code, boolean down) {
        if (key.code() == Keys.NONE || code != key.code()) return false;
        boolean before = active;
        if (mode.val() == Mode.HOLD) active = down;
        else if (down) active = !active;
        return before != active;
    }

    public boolean active() { return active; }
    public void setActive(boolean on) { active = on; }
}
