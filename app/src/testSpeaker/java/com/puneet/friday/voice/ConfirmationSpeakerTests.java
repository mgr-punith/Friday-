package com.puneet.friday.voice;

import android.content.Context;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import com.puneet.friday.DebugTools;
import com.puneet.friday.ui.CompletionPolicy;
import java.nio.file.Path;
import java.util.List;
import kotlin.Unit;

/** Runs the production speaker bytecode, not a duplicate lifecycle implementation. */
public final class ConfirmationSpeakerTests {
    private static int checks;

    private static final class Screen {
        int callbacks;
        int completions;
        ConfirmationSpeaker speaker() {
            return new ConfirmationSpeaker(new Context(),
                speaking -> { callbacks++; return Unit.INSTANCE; },
                audio -> { callbacks++; return Unit.INSTANCE; },
                available -> { callbacks++; return Unit.INSTANCE; },
                () -> { callbacks++; completions++; return Unit.INSTANCE; });
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // SharedTextToSpeech is a process singleton; each engine-failure scenario gets a fresh JVM.
            for (String scenario : List.of("detached", "launcher", "replacement", "missing", "language", "rejected", "startup-timeout", "playback-timeout")) {
                Process child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    ConfirmationSpeakerTests.class.getName(), scenario).inheritIO().start();
                expect(child.waitFor() == 0, "Speaker lifecycle scenario failed: " + scenario);
            }
            return;
        }
        switch (args[0]) {
            case "detached" -> detached();
            case "launcher" -> launcher();
            case "replacement" -> replacement();
            case "missing" -> unavailable(false);
            case "language" -> unavailable(true);
            case "rejected" -> rejected();
            case "startup-timeout" -> timeout(false);
            case "playback-timeout" -> timeout(true);
            default -> throw new AssertionError("Unknown speaker scenario");
        }
        expect(DebugTools.INSTANCE.activeSpeakers() == 0, "Speech cleanup releases the wake gate");
        expect(Handler.pendingTasks() == 0, "Speech cleanup removes watchdog and waveform tasks");
        System.out.println(checks + " confirmation speaker checks passed (" + args[0] + ")");
    }

    private static void detached() {
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speak("Timer request sent for 2 minutes");
        expect(DebugTools.INSTANCE.activeSpeakers() == 1, "Pending TTS startup pauses wake capture");
        expect(speaker.finishAfterScreenCloses(), "A pending timer confirmation survives screen closure");
        expect(speaker.finishAfterScreenCloses(), "Repeated closure keeps one pending confirmation");
        speaker.shutdown(); // Activity.onDestroy follows the screen closure.
        Handler.idle();
        TextToSpeech engine = TextToSpeech.current;
        expect(engine.utterances.size() == 1, "Activity destruction does not cancel pending timer speech");
        TextToSpeech.Utterance utterance = engine.utterances.get(0);
        expect(utterance.text().equals("Timer request sent for 2 minutes"), "Timer speech preserves honest wording");
        expect(engine.stops == 0, "Activity destruction does not stop detached playback");
        expect(screen.callbacks == 0, "Detached engine startup and playback do not call the closed screen");
        utterance.listener().onBeginSynthesis(utterance.id(), 16_000, 2, 1);
        utterance.listener().onAudioAvailable(utterance.id(), new byte[1_280]);
        Handler.idle();
        expect(screen.callbacks == 0 && Handler.pendingTasks() == 1, "Detached playback schedules no UI waveform");
        expect(DebugTools.INSTANCE.activeSpeakers() == 1, "Wake stays paused until detached speech finishes");
        utterance.listener().onDone(utterance.id());
        Handler.idle();
        expect(engine.listener == null && engine.stops == 0, "Completed speech releases its listener without interruption");
        expect(screen.callbacks == 0 && screen.completions == 0, "Detached completion does not notify the old Activity");
        utterance.listener().onDone(utterance.id());
        Handler.idle();
        expect(!speaker.finishAfterScreenCloses(), "Finished speech cannot detach again");
    }

    private static void replacement() {
        TextToSpeech.autoInitialize = false;
        Screen oldScreen = new Screen();
        ConfirmationSpeaker old = oldScreen.speaker();
        old.speak("Timer request sent for 2 minutes");
        old.finishAfterScreenCloses();
        old.shutdown();
        Handler.idle();
        TextToSpeech engine = TextToSpeech.current;
        ConfirmationSpeaker.Companion.stopPlayback(); // New command or sign-out.
        expect(DebugTools.INSTANCE.activeSpeakers() == 0, "New capture cancels detached startup before it speaks");
        Screen nextScreen = new Screen();
        ConfirmationSpeaker next = nextScreen.speaker();
        next.speak("Opening Calculator");
        Handler.idle();
        engine.initialize(TextToSpeech.SUCCESS);
        Handler.idle();
        expect(engine.utterances.size() == 1, "Late engine readiness cannot speak a cancelled timer");
        TextToSpeech.Utterance first = engine.utterances.get(0);
        expect(first.text().equals("Opening Calculator"), "Only the replacement confirmation reaches TTS");
        next.finishAfterScreenCloses();
        next.shutdown();
        Screen thirdScreen = new Screen();
        ConfirmationSpeaker third = thirdScreen.speaker();
        third.speak("Silent mode on");
        Handler.idle();
        TextToSpeech.Utterance second = engine.utterances.get(1);
        expect(!first.id().equals(second.id()), "Replacement Activity uses a different utterance identity");
        first.listener().onDone(first.id());
        first.listener().onStop(first.id(), true);
        first.listener().onError(first.id());
        next.shutdown();
        old.shutdown();
        Handler.idle();
        expect(DebugTools.INSTANCE.activeSpeakers() == 1, "Old callbacks and shutdown cannot release the new speaker");
        expect(engine.listener == second.listener(), "Old shutdown cannot clear the replacement TTS listener");
        expect(thirdScreen.completions == 0, "Old callbacks cannot finish the new command");
        expect(oldScreen.callbacks == 0, "Cancelled detached startup drops the old UI callbacks");
        second.listener().onDone(second.id());
        Handler.idle();
        expect(thirdScreen.completions == 1, "The current command completes once");
        second.listener().onDone(second.id());
        Handler.idle();
        expect(thirdScreen.completions == 1, "Duplicate completion is ignored");
        third.shutdown();
    }

    private static void launcher() {
        Screen home = new Screen();
        ConfirmationSpeaker speaker = home.speaker();
        speaker.speak("Timer request sent for 2 seconds");
        var pause = CompletionPolicy.INSTANCE.onPause(false, true, false, false);
        expect(pause.getKeepConfirmation() && !pause.getResetUi() && !pause.getCloseScreen(),
            "Launcher Clock handoff preserves speech and result without finishing Home");
        expect(speaker.finishAfterScreenCloses(), "Launcher keeps queued speech when Clock takes focus");
        Handler.idle();
        TextToSpeech engine = TextToSpeech.current;
        expect(engine.utterances.size() == 1 && engine.stops == 0, "Launcher pause does not cut off timer speech");
        var timer = engine.utterances.get(0);
        expect(home.callbacks == 0, "Hidden Home receives no speech callbacks");
        // Returning Home binds a fresh speaker to the same shared engine.
        Screen resumed = new Screen();
        ConfirmationSpeaker next = resumed.speaker();
        timer.listener().onDone(timer.id());
        Handler.idle();
        expect(home.callbacks == 0 && DebugTools.INSTANCE.activeSpeakers() == 0,
            "Timer completion releases wake capture without changing the hidden Home");
        next.speak("Timer request sent for 2 minutes");
        Handler.idle();
        expect(TextToSpeech.current == engine && engine.utterances.size() == 2,
            "Another launcher command reuses the TTS engine after the previous speaker closes");
        var repeated = engine.utterances.get(1);
        expect(!timer.id().equals(repeated.id()), "Repeated launcher commands use distinct utterance IDs");
        timer.listener().onStop(timer.id(), true);
        Handler.idle();
        expect(resumed.completions == 0 && engine.listener == repeated.listener(),
            "An old Home callback cannot finish or silence the next confirmation");
        repeated.listener().onDone(repeated.id());
        Handler.idle();
        expect(resumed.completions == 1, "The new Home command completes once through its own callbacks");
        next.shutdown();
    }

    private static void unavailable(boolean missingLanguage) {
        if (missingLanguage) TextToSpeech.languageStatus = TextToSpeech.ERROR;
        else TextToSpeech.initializationStatus = TextToSpeech.ERROR;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speak("Timer request sent for 2 minutes");
        speaker.finishAfterScreenCloses();
        speaker.shutdown();
        Handler.idle();
        expect(TextToSpeech.current.utterances.isEmpty(), "Unavailable English TTS submits no playback");
        expect(TextToSpeech.current.shutdowns == 1, "Failed device engine releases its connection");
        expect(screen.callbacks == 0, "Missing TTS never calls a destroyed screen");
        expect(!speaker.finishAfterScreenCloses(), "Missing TTS finishes detached cleanup");
    }

    private static void rejected() {
        TextToSpeech.speakStatus = TextToSpeech.ERROR;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speak("Timer request sent for 2 minutes");
        speaker.finishAfterScreenCloses();
        speaker.shutdown();
        Handler.idle();
        expect(TextToSpeech.current.listener == null, "Rejected playback releases the TTS listener");
        expect(screen.callbacks == 0, "Rejected detached speech skips old-screen feedback");
        expect(!speaker.finishAfterScreenCloses(), "Rejected playback closes the detached speaker");
    }

    private static void timeout(boolean initialized) {
        TextToSpeech.autoInitialize = initialized;
        Screen screen = new Screen();
        ConfirmationSpeaker speaker = screen.speaker();
        speaker.speak("Timer request sent for 2 minutes");
        speaker.finishAfterScreenCloses();
        speaker.shutdown();
        Handler.idle();
        TextToSpeech engine = TextToSpeech.current;
        expect(engine.utterances.size() == (initialized ? 1 : 0), "Timeout fixture selects startup or playback stall");
        Handler.advance(11_999);
        expect(DebugTools.INSTANCE.activeSpeakers() == 1, "Watchdog allows the short confirmation to finish");
        Handler.advance(1);
        expect(engine.stops == 1 && engine.listener == null, "Watchdog stops stalled speech after twelve seconds");
        expect(DebugTools.INSTANCE.activeSpeakers() == 0, "Watchdog releases wake listening after a TTS stall");
        if (initialized) {
            TextToSpeech.Utterance utterance = engine.utterances.get(0);
            utterance.listener().onDone(utterance.id());
        } else {
            engine.initialize(TextToSpeech.SUCCESS);
        }
        Handler.idle();
        expect(engine.utterances.size() == (initialized ? 1 : 0), "Late engine callbacks cannot restart expired speech");
        expect(screen.callbacks == 0, "Watchdog and late engine callbacks never touch the old screen");
    }

    private static void expect(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
