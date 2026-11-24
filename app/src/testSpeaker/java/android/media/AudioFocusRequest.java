package android.media;

import android.os.Handler;

public final class AudioFocusRequest {
    public int gain;
    public AudioAttributes attributes;
    public AudioManager.OnAudioFocusChangeListener listener;
    public Handler handler;

    public static final class Builder {
        private final AudioFocusRequest request = new AudioFocusRequest();
        public Builder(int gain) { request.gain = gain; }
        public Builder setAudioAttributes(AudioAttributes attributes) {
            request.attributes = attributes; return this;
        }
        public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener listener, Handler handler) {
            request.listener = listener; request.handler = handler; return this;
        }
        public AudioFocusRequest build() { return request; }
    }
}
