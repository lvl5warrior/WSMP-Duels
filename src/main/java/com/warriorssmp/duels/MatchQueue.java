package com.warriorssmp.duels;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A simple FIFO queue of pending match requests for one map (1v1 or
 * Gauntlet) - when the map is occupied, a new request goes to the back
 * of this queue instead of starting immediately; when the current match
 * on that map ends, the next entry is pulled automatically. Generic over
 * whatever "one queued request" means for that mode - DuelManager and
 * GauntletManager each define their own small entry type to hold what
 * they actually need to start that request once it's its turn.
 */
public class MatchQueue<T> {

    private final Deque<T> queue = new ArrayDeque<>();

    public void enqueue(T entry) {
        queue.addLast(entry);
    }

    public T dequeueNext() {
        return queue.pollFirst();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public boolean remove(T entry) {
        return queue.remove(entry);
    }

    /** A snapshot list, in queue order (first = next up) - safe to hand
     *  to a GUI without exposing the live queue itself to be mutated
     *  from outside this class. */
    public List<T> snapshot() {
        return new ArrayList<>(queue);
    }
}
