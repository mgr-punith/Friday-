package com.puneet.friday.actions;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production resolver from Friday's worker thread with platform doubles. */
public final class PreviousTrackMediaSessionTests {
    private static final long CONTROLS = PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Looper.getMainLooper();
        discoversPlayerWithoutDefaultCategory();
        uniquePlayer();
        unavailablePlayers();
        timeoutAndLateCallback();
        interruptedConnection();
        expect(Handler.pendingTasks() == 0, "Resolver leaves no queued work");
        System.out.println(checks + " previous-track session checks passed");
    }

    private static MediaSession.Token player(Context context, String name, int state, long controls) {
        ServiceInfo info = new ServiceInfo();
        info.packageName = "example." + name; info.name = name; info.exported = true;
        ResolveInfo resolved = new ResolveInfo(); resolved.serviceInfo = info;
        context.packageManager.services.add(resolved);
        MediaSession.Token token = new MediaSession.Token(new PlaybackState(state, controls));
        MediaBrowser.SESSIONS.put(new ComponentName(info.packageName, info.name), token);
        return token;
    }

    private static MediaController resolve(Context context, boolean interrupt) throws Exception {
        AtomicReference<MediaController> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean[] interrupted = {false};
        Thread worker = new Thread(() -> {
            try { result.set(new PreviousTrackMediaSessionResolver(context).findController()); }
            catch (Throwable error) { failure.set(error); }
            interrupted[0] = Thread.currentThread().isInterrupted();
        }, "FridayActionTest");
        worker.start();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (worker.isAlive() && System.nanoTime() < deadline) {
            Handler.idle();
            if (interrupt && !MediaBrowser.CREATED.isEmpty()) worker.interrupt();
            worker.join(2);
        }
        expect(!worker.isAlive(), "Media discovery has a bounded wait");
        Handler.idle();
        if (failure.get() != null) throw new AssertionError("Media discovery must not crash", failure.get());
        if (interrupt) expect(interrupted[0], "Discovery preserves worker interruption");
        for (MediaBrowser browser : MediaBrowser.CREATED) expect(browser.disconnected, "Every opened browser disconnects");
        return result.get();
    }

    private static void discoversPlayerWithoutDefaultCategory() throws Exception {
        MediaBrowser.reset();
        Context context = new Context();
        MediaSession.Token expected = player(context, "music", PlaybackState.STATE_PLAYING, CONTROLS);
        MediaController result = resolve(context, false);
        expect(result != null && result.getSessionToken() == expected,
            "Worker finds the player whose browser service omits CATEGORY_DEFAULT");
        expect("android.media.browse.MediaBrowserService".equals(context.packageManager.lastAction),
            "Discovery queries media browser services");
        expect(new PreviousTrackMediaSessionResolver(context).findController() == null,
            "A caller on the main thread never blocks waiting for its own callbacks");
    }

    private static void uniquePlayer() throws Exception {
        MediaBrowser.reset();
        Context context = new Context();
        MediaSession.Token expected = player(context, "playing", PlaybackState.STATE_PLAYING, CONTROLS);
        player(context, "paused", PlaybackState.STATE_PAUSED, CONTROLS);
        expect(resolve(context, false).getSessionToken() == expected, "One playing session wins over a paused player");
        player(context, "second", PlaybackState.STATE_PLAYING, CONTROLS);
        expect(resolve(context, false) == null, "Two playing sessions do not guess a target");
        MediaBrowser.reset();
        context = new Context();
        expected = player(context, "paused", PlaybackState.STATE_PAUSED, CONTROLS);
        expect(resolve(context, false).getSessionToken() == expected, "A single paused player can receive Previous");
        player(context, "second", PlaybackState.STATE_PAUSED, CONTROLS);
        expect(resolve(context, false) == null, "Two paused sessions do not guess a target");
    }

    private static void unavailablePlayers() throws Exception {
        MediaBrowser.reset();
        Context context = new Context();
        expect(resolve(context, false) == null && MediaBrowser.attempts == 0, "No player needs no connection");
        player(context, "private", PlaybackState.STATE_PLAYING, CONTROLS);
        context.packageManager.services.get(0).serviceInfo.exported = false;
        expect(resolve(context, false) == null && MediaBrowser.attempts == 0, "Private services are excluded");
        context.packageManager.services.get(0).serviceInfo.exported = true;
        MediaBrowser.failConnect = true;
        expect(resolve(context, false) == null, "A rejected connection permits the media-button fallback");
        MediaBrowser.reset();
        context = new Context();
        player(context, "noSeek", PlaybackState.STATE_PLAYING, PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        player(context, "stopped", PlaybackState.STATE_STOPPED, CONTROLS);
        expect(resolve(context, false) == null, "A stopped player or missing seek support cannot receive the two-step request");
        MediaBrowser.SESSIONS.clear();
        expect(resolve(context, false) == null, "A service that rejects browsing permits fallback");
    }

    private static void timeoutAndLateCallback() throws Exception {
        MediaBrowser.reset();
        Context context = new Context();
        player(context, "slow", PlaybackState.STATE_PLAYING, CONTROLS);
        MediaBrowser.delayCallbacks = true;
        expect(resolve(context, false) == null, "A stalled service times out");
        for (MediaBrowser browser : MediaBrowser.CREATED) browser.complete();
        Handler.idle();
        expect(Handler.pendingTasks() == 0, "Late callbacks cannot restart or retain a timed-out connection");
    }

    private static void interruptedConnection() throws Exception {
        MediaBrowser.reset();
        Context context = new Context();
        player(context, "interrupted", PlaybackState.STATE_PLAYING, CONTROLS);
        MediaBrowser.delayCallbacks = true;
        expect(resolve(context, true) == null, "Cancellation returns no stale player");
    }

    private static void expect(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
