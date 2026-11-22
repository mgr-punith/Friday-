package com.puneet.friday;

import java.util.HashSet;
import java.util.Set;

/** Records the real speaker's wake-gate calls without loading Android services. */
public final class DebugTools {
    public static final DebugTools INSTANCE = new DebugTools();
    private final Set<Object> speakers = new HashSet<>();
    public void beginSpeechPlayback(Object owner) { speakers.add(owner); }
    public void endSpeechPlayback(Object owner) { speakers.remove(owner); }
    public int activeSpeakers() { return speakers.size(); }
}
