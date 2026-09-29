package gg.aether.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small typed event bus with priorities. A throwing handler is isolated so one bad mod cannot break others. */
public final class EventBus {
    public interface Handler<E> { void handle(E event); }

    private static final class Entry {
        final int priority;
        final Object owner;
        final Handler<Object> handler;
        Entry(int p, Object o, Handler<Object> h) { priority = p; owner = o; handler = h; }
    }

    private final Map<Class<?>, List<Entry>> handlers = new HashMap<Class<?>, List<Entry>>();
    private final List<String> errors = new ArrayList<String>();

    /** Lower priority value runs first. */
    @SuppressWarnings("unchecked")
    public synchronized <E> void on(Class<E> type, Object owner, int priority, Handler<E> h) {
        List<Entry> l = handlers.get(type);
        if (l == null) {
            l = new ArrayList<Entry>();
            handlers.put(type, l);
        }
        l.add(new Entry(priority, owner, (Handler<Object>) (Handler<?>) h));
        Collections.sort(l, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Integer.compare(a.priority, b.priority); }
        });
    }

    public <E> void on(Class<E> type, Object owner, Handler<E> h) { on(type, owner, 0, h); }

    public synchronized void unregister(Object owner) {
        for (List<Entry> l : handlers.values()) {
            for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).owner == owner) l.remove(i);
        }
    }

    public void post(Object event) {
        List<Entry> snapshot;
        synchronized (this) {
            List<Entry> l = handlers.get(event.getClass());
            if (l == null) return;
            snapshot = new ArrayList<Entry>(l);
        }
        for (Entry e : snapshot) {
            try {
                e.handler.handle(event);
            } catch (RuntimeException ex) {
                synchronized (this) { errors.add(event.getClass().getSimpleName() + ": " + ex); }
            }
        }
    }

    public synchronized List<String> errors() { return new ArrayList<String>(errors); }
}
