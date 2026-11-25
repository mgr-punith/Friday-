package com.puneet.friday.voice;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import com.puneet.friday.DebugTools;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import kotlin.Unit;

/** Exercises production cloud playback, fallback and cancellation without a device engine. */
public final class CloudSpeechTests {
    private static int checks;
    private static final String ANSWER = "Sachin Tendulkar is a former Indian cricketer.";
    private static final class Screen {
        final Context context = new Context();
        int starts;
        int levels;
        int completions;
        int interruptions;
        int availabilityChanges;
        boolean available;
        ConfirmationSpeaker speaker() {
            return new ConfirmationSpeaker(context,
                speaking -> { if (speaking) starts++; return Unit.INSTANCE; },
                audio -> { levels++; return Unit.INSTANCE; },
                available -> { this.available = available; availabilityChanges++; return Unit.INSTANCE; },
                () -> { completions++; return Unit.INSTANCE; },
                () -> { interruptions++; return Unit.INSTANCE; });
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            for (String scenario : List.of("playback", "missing-device", "failure", "invalid", "timeout", "stalled-track",
                "cancel", "replacement", "focus", "focus-denied", "track-failure", "partial-error", "clarification", "no-engines",
                "fallback-interrupted", "fallback-error", "fallback-error-legacy", "late-device-failure", "play-stop-race", "play-focus-race")) {
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CloudSpeechTests.class.getName(), scenario).inheritIO().start();
                expect(child.waitFor() == 0, "Cloud speech scenario failed: " + scenario);
            }
            return;
        }
        switch (args[0]) {
            case "playback" -> playback(false, false);
            case "missing-device" -> playback(true, false);
            case "clarification" -> playback(false, true);
            case "failure" -> fallback(false, false);
            case "invalid" -> fallback(true, false);
            case "no-engines" -> fallback(false, true);
            case "timeout" -> timeout();
            case "stalled-track" -> stalledTrack();
            case "cancel" -> cancel(false);
            case "replacement" -> cancel(true);
            case "focus" -> interruption(false, false);
            case "focus-denied" -> interruption(true, false);
            case "partial-error" -> interruption(false, true);
            case "track-failure" -> trackFailure();
            case "fallback-interrupted" -> fallbackInterrupted(0);
            case "fallback-error" -> fallbackInterrupted(1);
            case "fallback-error-legacy" -> fallbackInterrupted(2);
            case "late-device-failure" -> lateDeviceFailure();
            case "play-stop-race" -> playInterruptionRace(false);
            case "play-focus-race" -> playInterruptionRace(true);
            default -> throw new AssertionError("Unknown scenario");
        }
        settleSpeechWorkers();
        waitFor(() -> AudioTrack.tracks.stream().allMatch(track -> track.released));
        expect(DebugTools.INSTANCE.activeSpeakers() == 0, "Completion releases the wake gate");
        expect(Handler.pendingTasks() == 0, "Completion removes speech timers and callbacks");
        expect(AudioTrack.tracks.stream().allMatch(track -> track.releases == 1), "Each track releases once");
        System.out.println(checks + " cloud speech checks passed (" + args[0] + ")");
    }
    private static void playback(boolean missingDevice, boolean clarification) throws Exception {
        if (missingDevice) TextToSpeech.initializationStatus = TextToSpeech.ERROR;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        if (missingDevice) { speaker.warmUp(); Handler.idle(); }
        String text = clarification ? "Which Gandhi do you mean?" : ANSWER;
        speaker.speakAnswer(text, cancelled -> wav());
        waitFor(() -> !AudioTrack.tracks.isEmpty() && AudioTrack.tracks.get(0).written == 1_600);
        AudioTrack track = AudioTrack.tracks.get(0);
        expect(screen.starts == 0 && screen.completions == 0 && screen.levels == 0,
            "Queued audio does not claim to be speaking or finish a clarification");
        expect(track.format.sampleRate == 16_000 && track.format.channelMask == 4 && track.format.encoding == 2,
            "Cloud speech uses mono PCM16 at 16 kHz");
        expect(track.attributes.usage == 16 && track.transferMode == AudioTrack.MODE_STATIC,
            "Preloaded cloud speech uses assistant audio and can play short clips");
        track.head = 640;
        waitFor(() -> screen.starts == 1 && screen.levels == 1);
        expect(DebugTools.INSTANCE.activeSpeakers() == 1, "Wake capture stays paused while speech plays");
        expect(screen.completions == 0, "Reply handoff waits for the playback head to reach the end");
        track.head = 1_600;
        waitFor(() -> screen.completions == 1);
        expect(screen.interruptions == 0 && screen.starts == 1, "Completed speech calls the normal handoff once");
        expect(TextToSpeech.current == null || TextToSpeech.current.utterances.isEmpty(), "Cloud speech needs no device engine");
        expect(screen.context.audioManager.abandons == 1, "Completion releases audio focus");
        speaker.shutdown();
    }
    private static void fallback(boolean invalid, boolean missing) throws Exception {
        if (missing) TextToSpeech.initializationStatus = TextToSpeech.ERROR;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speakAnswer(ANSWER, cancelled -> {
            if (invalid) return new byte[]{1, 2, 3};
            throw new IllegalStateException("speech_unavailable");
        });
        waitFor(() -> missing ? screen.completions == 1 : TextToSpeech.current != null && !TextToSpeech.current.utterances.isEmpty());
        expect(AudioTrack.tracks.isEmpty(), "Failed or malformed cloud audio never reaches AudioTrack");
        if (!missing) finishDevice(screen);
        else expect(screen.starts == 0 && screen.interruptions == 0, "A phone without either engine completes without crashing");
        speaker.shutdown();
    }
    private static void timeout() throws Exception {
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        speaker.speakAnswer(ANSWER, cancelled -> { await(release); byte[] bytes = wav(); returned.countDown(); return bytes; });
        Handler.advance(5_000);
        waitFor(() -> TextToSpeech.current != null && TextToSpeech.current.utterances.size() == 1);
        release.countDown();
        expect(returned.await(2, TimeUnit.SECONDS), "Slow fetch can return after fallback");
        settleSpeechWorkers();
        expect(AudioTrack.tracks.isEmpty(), "Late cloud results cannot replay after the fallback deadline");
        finishDevice(screen);
        speaker.shutdown();
    }
    private static void stalledTrack() throws Exception {
        AudioTrack.acceptWrites = false;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speakAnswer(ANSWER, cancelled -> wav());
        waitFor(() -> !AudioTrack.tracks.isEmpty() && AudioTrack.tracks.get(0).writes > 0);
        Handler.advance(5_000);
        waitFor(() -> TextToSpeech.current != null && TextToSpeech.current.utterances.size() == 1);
        expect(screen.starts == 1, "Stalled AudioTrack falls back without claiming cloud playback started");
        finishDevice(screen);
        speaker.shutdown();
    }
    private static void cancel(boolean replace) throws Exception {
        Screen oldScreen = new Screen();
        ConfirmationSpeaker old = oldScreen.speaker();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        boolean[] cancellation = new boolean[1];
        old.speakAnswer(ANSWER, cancelled -> {
            await(release); cancellation[0] = cancelled.invoke(); returned.countDown(); return wav();
        });
        Screen nextScreen = new Screen();
        ConfirmationSpeaker next = nextScreen.speaker();
        if (replace) next.speak("Opening Calculator"); else old.stop();
        Handler.idle();
        release.countDown();
        expect(returned.await(2, TimeUnit.SECONDS) && cancellation[0], "Cancellation reaches the background fetch");
        settleSpeechWorkers();
        expect(AudioTrack.tracks.isEmpty() && oldScreen.starts == 0 && oldScreen.completions == 0,
            "Cancelled fetch cannot replay speech or finish a replacement screen");
        if (replace) finishDevice(nextScreen);
        old.shutdown(); next.shutdown();
    }
    private static void interruption(boolean deny, boolean partialError) throws Exception {
        Screen screen = new Screen();
        if (deny) screen.context.audioManager.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speakAnswer("Which Gandhi do you mean?", cancelled -> wav());
        if (!deny) {
            waitFor(() -> !AudioTrack.tracks.isEmpty() && AudioTrack.tracks.get(0).written == 1_600);
            AudioTrack track = AudioTrack.tracks.get(0);
            track.head = 640;
            waitFor(() -> screen.starts == 1);
            if (partialError) track.failPlaybackHead = true;
            else { screen.context.audioManager.dispatch(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT); Handler.idle(); }
        }
        waitFor(() -> screen.interruptions == 1);
        expect(screen.completions == 0, "Interrupted clarification cannot trigger reply recording");
        expect(TextToSpeech.current == null, "Focus loss cannot replay device speech over the interrupting app");
        expect(screen.context.audioManager.abandons == 1, "Interrupted playback releases its focus request");
        speaker.shutdown();
    }
    private static void trackFailure() throws Exception {
        AudioTrack.failBuild = true;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speakAnswer(ANSWER, cancelled -> wav());
        waitFor(() -> TextToSpeech.current != null && TextToSpeech.current.utterances.size() == 1);
        expect(screen.context.audioManager.abandons == 1, "Failed track startup releases cloud audio focus");
        finishDevice(screen);
        speaker.shutdown();
    }
    private static void fallbackInterrupted(int failure) throws Exception {
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speakAnswer("Which Gandhi do you mean?", cancelled -> null);
        waitFor(() -> TextToSpeech.current != null && TextToSpeech.current.utterances.size() == 1 && screen.starts == 1);
        TextToSpeech.Utterance utterance = TextToSpeech.current.utterances.get(0);
        if (failure == 0) utterance.listener().onStop(utterance.id(), true);
        else if (failure == 1) utterance.listener().onError(utterance.id(), -1);
        else utterance.listener().onError(utterance.id());
        Handler.idle();
        expect(screen.interruptions == 1 && screen.completions == 0,
            "Interrupted or failed device fallback cannot trigger clarification reply capture");
        expect(TextToSpeech.current.listener == null, "Failed fallback releases its engine listener");
        utterance.listener().onDone(utterance.id());
        utterance.listener().onStop(utterance.id(), true);
        Handler.idle();
        expect(screen.interruptions == 1 && screen.completions == 0, "Late device completion cannot resume a cancelled clarification");
        speaker.shutdown();
    }
    private static void lateDeviceFailure() throws Exception {
        TextToSpeech.autoInitialize = false;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speak("Opening Calculator"); Handler.idle();
        TextToSpeech engine = TextToSpeech.current;
        speaker.speakAnswer("Which Gandhi do you mean?", cancelled -> wav());
        waitFor(() -> !AudioTrack.tracks.isEmpty() && AudioTrack.tracks.get(0).written == 1_600);
        AudioTrack track = AudioTrack.tracks.get(0);
        track.head = 640;
        waitFor(() -> screen.starts == 1);
        expect(screen.availabilityChanges == 1 && screen.available, "Cloud speech owns availability after replacement");
        engine.initialize(TextToSpeech.ERROR); Handler.idle();
        expect(screen.availabilityChanges == 1 && screen.available,
            "Late cancelled engine startup cannot hide cloud playback controls or schedule early reply capture");
        expect(screen.completions == 0 && screen.interruptions == 0, "Late engine failure cannot finish active cloud speech");
        track.head = 1_600;
        waitFor(() -> screen.completions == 1);
        expect(engine.utterances.isEmpty(), "Replaced device utterance cannot speak after cloud playback");
        speaker.shutdown();
    }
    private static void playInterruptionRace(boolean focusLoss) throws Exception {
        Context context = new Context();
        int[] callbacks = new int[4];
        AudioTrack.playEntered = new CountDownLatch(1);
        AudioTrack.continuePlay = new CountDownLatch(1);
        SpeechAudioPlayer player = new SpeechAudioPlayer(context, new SpeechAudio(new short[1_600]),
            () -> { callbacks[0]++; return Unit.INSTANCE; }, level -> Unit.INSTANCE,
            () -> { callbacks[1]++; return Unit.INSTANCE; }, started -> { callbacks[2]++; return Unit.INSTANCE; },
            () -> { callbacks[3]++; return Unit.INSTANCE; });
        player.start();
        expect(AudioTrack.playEntered.await(2, TimeUnit.SECONDS), "Playback fixture stops inside platform play");
        AudioTrack track = AudioTrack.tracks.get(0);
        CountDownLatch interruptRequested = new CountDownLatch(1);
        Thread interrupter = new Thread(() -> {
            interruptRequested.countDown();
            if (focusLoss) context.audioManager.request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            else player.stop();
        }, "Friday-test-play-interruption");
        interrupter.start();
        expect(interruptRequested.await(2, TimeUnit.SECONDS), "Cancellation races with platform start");
        waitFor(() -> !interrupter.isAlive() || interrupter.getState() == Thread.State.BLOCKED);
        boolean cancelledBeforePlayReturned = !interrupter.isAlive();
        AudioTrack.continuePlay.countDown();
        interrupter.join(2_000);
        expect(!interrupter.isAlive(), "Cancellation settles after platform start returns");
        waitFor(() -> track.released && (!focusLoss || callbacks[3] == 1));
        expect(!cancelledBeforePlayReturned && !track.restartedAfterPause && !track.playing,
            "Serialized cancellation prevents play from restarting audio after pause completes");
        expect(callbacks[0] == 0 && callbacks[1] == 0 && callbacks[2] == 0,
            "Cancelled startup cannot claim playback, normal completion or device replay");
        expect(callbacks[3] == (focusLoss ? 1 : 0), "Focus interruption completes once while explicit cancellation stays silent");
    }
    private static void finishDevice(Screen screen) {
        TextToSpeech.Utterance utterance = TextToSpeech.current.utterances.get(0);
        expect(TextToSpeech.current.utterances.size() == 1, "Fallback submits one device utterance");
        utterance.listener().onDone(utterance.id()); Handler.idle();
        expect(screen.completions == 1 && screen.interruptions == 0, "Fallback completes once");
    }
    private static byte[] wav() {
        ByteBuffer bytes = ByteBuffer.allocate(3_244).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes()).putInt(3_236).put("WAVEfmt ".getBytes()).putInt(16);
        bytes.putShort((short)1).putShort((short)1).putInt(16_000).putInt(32_000).putShort((short)2).putShort((short)16);
        bytes.put("data".getBytes()).putInt(3_200);
        for (int i = 0; i < 1_600; i++) bytes.putShort((short)(Math.sin(i * .1) * 5_000));
        return bytes.array();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(4, TimeUnit.SECONDS)) throw new AssertionError("Fetch latch timed out"); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }
    private static void waitFor(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do { Handler.idle(); if (ready.getAsBoolean()) return; Thread.sleep(5); } while (System.nanoTime() < deadline);
        throw new AssertionError("Cloud speech event did not arrive");
    }
    private static void settleSpeechWorkers() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (true) {
            Handler.idle();
            List<Thread> workers = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && (thread.getName().equals("Friday-speech-fetch") ||
                    thread.getName().equals("Friday-speech-playback"))).toList();
            if (workers.isEmpty()) {
                Handler.idle();
                if (Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() &&
                    (thread.getName().equals("Friday-speech-fetch") || thread.getName().equals("Friday-speech-playback")))) return;
                continue;
            }
            for (Thread worker : workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("Speech worker did not stop: " + worker.getName());
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (worker.isAlive()) throw new AssertionError("Speech worker did not stop: " + worker.getName());
            }
        }
    }
    private static void expect(boolean value, String description) {
        if (!value) throw new AssertionError(description); checks++;
    }
}
