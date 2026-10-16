package gg.kite.mc;

import gg.kite.core.GameState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Autotest helper: every GameState query, evaluated once, as "name = value" lines for the log. */
public final class StateProbe {
    private StateProbe() {}

    public static List<String> dump(GameState s) {
        Map<String, String> out = new TreeMap<String, String>();
        int x = (int) Math.floor(s.x()), y = (int) Math.floor(s.y()), z = (int) Math.floor(s.z());
        for (Method m : GameState.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] args;
            if (p.length == 0) args = new Object[0];
            else if (Arrays.equals(p, new Class<?>[] { int.class, int.class, int.class })) args = new Object[] { x, y, z };
            else if (Arrays.equals(p, new Class<?>[] { int.class, int.class })) args = m.getName().equals("slimeChunk") ? new Object[] { x >> 4, z >> 4 } : new Object[] { x, z };
            else if (p.length == 1 && (p[0] == int.class || p[0] == double.class)) args = new Object[] { p[0] == int.class ? (Object) 16 : (Object) 16.0 };
            else if (p.length == 1 && p[0] == GameState.Key.class) args = new Object[] { GameState.Key.FORWARD };
            else if (m.getName().equals("caveColor")) args = new Object[] { x, z, y };
            else continue;
            String v;
            try {
                v = show(m.invoke(s, args));
            } catch (Exception e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                v = "!! " + c;
            }
            out.put(m.getName(), v);
        }
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, String> e : out.entrySet()) lines.add(e.getKey() + " = " + e.getValue());
        return lines;
    }

    private static String show(Object o) {
        if (o == null) return "null";
        if (o instanceof Collection) {
            Collection<?> c = (Collection<?>) o;
            List<String> items = new ArrayList<String>();
            int i = 0;
            for (Object e : c) {
                if (i++ >= 4) { items.add("..."); break; }
                items.add(show(e));
            }
            return c.size() + " " + items;
        }
        if (o instanceof Map) return ((Map<?, ?>) o).size() + " " + o;
        if (o.getClass().getName().startsWith("gg.kite.core.GameState$")) {
            StringBuilder b = new StringBuilder("{");
            for (Field f : o.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    b.append(f.getName()).append('=').append(f.get(o)).append(' ');
                } catch (IllegalAccessException ignored) { }
            }
            return b.append('}').toString();
        }
        return String.valueOf(o);
    }
}
