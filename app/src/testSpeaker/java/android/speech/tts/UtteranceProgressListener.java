package android.speech.tts;

public abstract class UtteranceProgressListener {
    public abstract void onStart(String id);
    public abstract void onDone(String id);
    public abstract void onError(String id);
    public void onError(String id, int code) { onError(id); }
    public void onStop(String id, boolean interrupted) {}
    public void onBeginSynthesis(String id, int rate, int format, int channels) {}
    public void onAudioAvailable(String id, byte[] audio) {}
}
