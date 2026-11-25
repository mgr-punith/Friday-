package android.media;

public final class AudioFormat {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int CHANNEL_OUT_MONO = 4;
    public int sampleRate;
    public int channelMask;
    public int encoding;
    public static final class Builder {
        private final AudioFormat format = new AudioFormat();
        public Builder setSampleRate(int value) { format.sampleRate = value; return this; }
        public Builder setChannelMask(int value) { format.channelMask = value; return this; }
        public Builder setEncoding(int value) { format.encoding = value; return this; }
        public AudioFormat build() { return format; }
    }
}
