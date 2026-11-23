package android.speech.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Device-engine double: tests control initialization, playback and late callbacks. */
public final class TextToSpeech {
    public interface OnInitListener { void onInit(int status); }
    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int QUEUE_FLUSH = 0;
    public static TextToSpeech current;
    public static boolean autoInitialize = true;
    public static int initializationStatus = SUCCESS;
    public static int languageStatus = SUCCESS;
    public static int speakStatus = SUCCESS;
    public static boolean networkVoice = false;
    public static boolean offlineAlternative = false;
    public static final class Engine {
        public static final String KEY_FEATURE_NOT_INSTALLED = "notInstalled";
    }
    public record Utterance(String text, String id, UtteranceProgressListener listener) {}
    public final List<Utterance> utterances = new ArrayList<>();
    public UtteranceProgressListener listener;
    public int stops;
    public int shutdowns;
    private final OnInitListener initialization;
    private Voice voice = new Voice(networkVoice);

    public TextToSpeech(Context context, OnInitListener initialization) {
        current = this;
        this.initialization = initialization;
        if (autoInitialize) new Handler(Looper.getMainLooper()).post(() -> initialize(initializationStatus));
    }
    public void initialize(int status) { initialization.onInit(status); }
    public int setLanguage(Locale locale) { voice = new Voice(networkVoice); return languageStatus; }
    public Voice getVoice() { return voice; }
    public Set<Voice> getVoices() { return offlineAlternative ? Set.of(voice, new Voice(false)) : Set.of(voice); }
    public int setVoice(Voice next) { voice = next; return SUCCESS; }
    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        this.listener = listener;
        return SUCCESS;
    }
    public int speak(CharSequence text, int queue, android.os.Bundle parameters, String id) {
        utterances.add(new Utterance(text.toString(), id, listener));
        if (speakStatus == SUCCESS) listener.onStart(id);
        return speakStatus;
    }
    public int stop() { stops++; return SUCCESS; }
    public void shutdown() { shutdowns++; }
}
