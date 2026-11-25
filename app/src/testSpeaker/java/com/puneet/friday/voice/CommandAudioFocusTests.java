package com.puneet.friday.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import kotlin.Unit;

/** Exercises the production focus lease with the same listener/Handler calls as Android. */
public final class CommandAudioFocusTests {
    private static int checks;

    public static void main(String[] args) {
        grantedAndReleased();
        unavailable();
        interrupted();
        captureFailure();
        staleCallback();
        expect(Handler.pendingTasks() == 0, "Focus checks leave no pending callbacks");
        System.out.println(checks + " command audio focus checks passed");
    }

    private static CommandAudioFocus focus(Context context, int[] interruptions) {
        return new CommandAudioFocus(context, () -> { interruptions[0]++; return Unit.INSTANCE; });
    }

    private static void grantedAndReleased() {
        Context context = new Context();
        int[] interruptions = {0};
        CommandAudioFocus focus = focus(context, interruptions);
        expect(focus.acquire(), "Granted focus allows recording");
        expect(!focus.acquire() && context.audioManager.requests == 1, "A capture requests focus once");
        expect(context.audioManager.request.gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            "Command capture interrupts playback rather than ducking it");
        expect(context.audioManager.request.attributes.usage == AudioAttributes.USAGE_ASSISTANT,
            "Focus identifies the assistant");
        expect(context.audioManager.request.attributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH,
            "Focus identifies speech capture");
        context.audioManager.dispatch(AudioManager.AUDIOFOCUS_GAIN);
        Handler.idle();
        expect(interruptions[0] == 0 && !focus.getInterrupted(), "Focus gain does not interrupt recording");
        focus.close();
        focus.close();
        expect(context.audioManager.abandons == 1, "Completion and repeated cancellation release once");
        expect(context.audioManager.abandoned == context.audioManager.request, "Release uses the original focus request");
        expect(!focus.acquire(), "A closed recording cannot reacquire focus");
    }

    private static void unavailable() {
        for (int result : new int[]{AudioManager.AUDIOFOCUS_REQUEST_FAILED, AudioManager.AUDIOFOCUS_REQUEST_DELAYED}) {
            Context context = new Context();
            context.audioManager.result = result;
            try (CommandAudioFocus focus = focus(context, new int[]{0})) {
                expect(!focus.acquire(), "Denied or delayed focus prevents microphone capture");
            }
            expect(context.audioManager.abandons == 1, "Denied focus leaves no pending request");
        }
        Context missing = new Context();
        missing.audioManager = null;
        try (CommandAudioFocus focus = focus(missing, new int[]{0})) {
            expect(!focus.acquire(), "Missing audio service prevents recording without crashing");
        }
        Context failing = new Context();
        failing.audioManager.failRequest = true;
        failing.audioManager.failAbandon = true;
        try (CommandAudioFocus focus = focus(failing, new int[]{0})) {
            expect(!focus.acquire(), "Audio service failure prevents recording without crashing");
        }
        expect(failing.audioManager.abandons == 1, "Service failure still attempts cleanup");
    }

    private static void interrupted() {
        for (int loss : new int[]{AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}) {
            Context context = new Context();
            int[] interruptions = {0};
            try (CommandAudioFocus focus = focus(context, interruptions)) {
                expect(focus.acquire(), "Capture owns focus before interruption");
                context.audioManager.dispatch(loss);
                context.audioManager.dispatch(loss);
                Handler.idle();
                expect(interruptions[0] == 1, "Focus loss stops the microphone once");
                expect(focus.getInterrupted(), "Caller can discard the interrupted command");
            }
            expect(context.audioManager.abandons == 1, "Interrupted capture releases focus");
        }
        Context racing = new Context();
        int[] interruptions = {0};
        CommandAudioFocus focus = focus(racing, interruptions);
        racing.audioManager.duringRequest = () -> {
            racing.audioManager.dispatch(AudioManager.AUDIOFOCUS_LOSS);
            Handler.idle();
        };
        try (focus) {
            expect(!focus.acquire() && interruptions[0] == 1, "Loss during acquisition prevents microphone startup");
        }
    }

    private static void captureFailure() {
        Context context = new Context();
        try (CommandAudioFocus focus = focus(context, new int[]{0})) {
            expect(focus.acquire(), "Capture begins with focus");
            throw new IllegalStateException("microphone_unavailable");
        } catch (IllegalStateException expected) {
            expect(context.audioManager.abandons == 1, "Microphone failure releases focus");
        }
    }

    private static void staleCallback() {
        Context context = new Context();
        int[] interruptions = {0};
        CommandAudioFocus old = focus(context, interruptions);
        expect(old.acquire(), "Earlier capture owns focus");
        context.audioManager.dispatch(AudioManager.AUDIOFOCUS_LOSS);
        old.close();
        try (CommandAudioFocus next = focus(context, interruptions)) {
            expect(next.acquire(), "Next capture can own focus");
            Handler.idle();
            expect(interruptions[0] == 0 && !old.getInterrupted() && !next.getInterrupted(),
                "A queued callback for a closed capture cannot interrupt the next one");
        }
        CommandAudioFocus unused = focus(context, interruptions);
        unused.close();
        expect(!unused.acquire() && context.audioManager.abandons == 2,
            "Cancel before acquisition makes no extra audio service calls");
    }

    private static void expect(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
