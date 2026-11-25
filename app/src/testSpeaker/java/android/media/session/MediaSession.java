package android.media.session;

public final class MediaSession {
    public static final class Token {
        public PlaybackState state;
        public Token(PlaybackState state) { this.state = state; }
    }
}
