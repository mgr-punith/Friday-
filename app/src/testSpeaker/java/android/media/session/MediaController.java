package android.media.session;

import android.content.Context;

public final class MediaController {
    private final MediaSession.Token token;
    public MediaController(Context context, MediaSession.Token token) { this.token = token; }
    public PlaybackState getPlaybackState() { return token.state; }
    public MediaSession.Token getSessionToken() { return token; }
}
