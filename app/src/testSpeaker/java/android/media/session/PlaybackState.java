package android.media.session;

public final class PlaybackState {
    public static final long ACTION_SKIP_TO_PREVIOUS = 16L;
    public static final long ACTION_SEEK_TO = 256L;
    public static final int STATE_STOPPED = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_PLAYING = 3;
    private final int state;
    private final long actions;
    public PlaybackState(int state, long actions) { this.state = state; this.actions = actions; }
    public int getState() { return state; }
    public long getActions() { return actions; }
}
