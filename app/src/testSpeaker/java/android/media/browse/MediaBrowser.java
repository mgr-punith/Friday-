package android.media.browse;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Models the browser's calling-thread requirement and asynchronous service callbacks. */
public final class MediaBrowser {
    public static final Map<ComponentName, MediaSession.Token> SESSIONS = new HashMap<>();
    public static final List<MediaBrowser> CREATED = new ArrayList<>();
    public static boolean failConnect;
    public static boolean delayCallbacks;
    public static int attempts;
    private final ConnectionCallback callback;
    private final MediaSession.Token token;
    public boolean disconnected;

    public MediaBrowser(Context context, ComponentName component, ConnectionCallback callback, Bundle hints) {
        attempts++;
        if (Looper.myLooper() == null) throw new RuntimeException("Browser requires a Looper");
        this.callback = callback;
        token = SESSIONS.get(component);
        CREATED.add(this);
    }
    public void connect() {
        if (failConnect) throw new SecurityException("Player rejected connection");
        if (!delayCallbacks) new Handler(Looper.getMainLooper()).post(this::complete);
    }
    public void complete() {
        if (token == null) callback.onConnectionFailed();
        else callback.onConnected();
    }
    public MediaSession.Token getSessionToken() { return token; }
    public void disconnect() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new AssertionError("Disconnect must use browser thread");
        disconnected = true;
    }
    public static void reset() {
        SESSIONS.clear(); CREATED.clear(); attempts = 0;
        failConnect = false; delayCallbacks = false;
    }
    public abstract static class ConnectionCallback {
        public void onConnected() {}
        public void onConnectionSuspended() {}
        public void onConnectionFailed() {}
    }
}
