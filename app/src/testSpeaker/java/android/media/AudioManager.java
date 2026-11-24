package android.media;

public final class AudioManager {
    public static final int AUDIOFOCUS_GAIN = 1;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4;
    public static final int AUDIOFOCUS_LOSS = -1;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -2;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3;
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;
    public static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    public static final int AUDIOFOCUS_REQUEST_DELAYED = 2;
    public interface OnAudioFocusChangeListener { void onAudioFocusChange(int change); }

    public int result = AUDIOFOCUS_REQUEST_GRANTED;
    public int requests;
    public int abandons;
    public boolean failRequest;
    public boolean failAbandon;
    public AudioFocusRequest request;
    public AudioFocusRequest abandoned;
    public Runnable duringRequest;

    public int requestAudioFocus(AudioFocusRequest value) {
        requests++;
        request = value;
        if (duringRequest != null) duringRequest.run();
        if (failRequest) throw new IllegalStateException("audio_service_unavailable");
        return result;
    }
    public int abandonAudioFocusRequest(AudioFocusRequest value) {
        abandons++;
        abandoned = value;
        if (failAbandon) throw new IllegalStateException("audio_service_unavailable");
        return AUDIOFOCUS_REQUEST_GRANTED;
    }
    public void dispatch(int change) {
        AudioFocusRequest target = request;
        target.handler.post(() -> target.listener.onAudioFocusChange(change));
    }
}
