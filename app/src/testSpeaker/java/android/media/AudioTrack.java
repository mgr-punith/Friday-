package android.media;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Platform double: tests move the playback head independently of writes. */
public final class AudioTrack {
    public static final int MODE_STREAM = 1;
    public static final int MODE_STATIC = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int WRITE_NON_BLOCKING = 1;
    public static final List<AudioTrack> tracks = new CopyOnWriteArrayList<>();
    public static boolean failBuild;
    public static boolean failWrite;
    public static boolean acceptWrites = true;
    public static boolean autoPlay;
    public static int minimum = 256;
    public static CountDownLatch playEntered;
    public static CountDownLatch continuePlay;
    public volatile boolean playing;
    public volatile boolean released;
    public volatile boolean failPlaybackHead;
    public volatile int written;
    public volatile int head;
    public volatile int writes;
    public volatile boolean playInProgress;
    public volatile boolean pausedDuringPlay;
    public volatile boolean restartedAfterPause;
    public int releases;
    public int pauses;
    public int flushes;
    public AudioAttributes attributes;
    public AudioFormat format;
    public int bufferBytes;
    public int transferMode;

    public static int getMinBufferSize(int rate, int channels, int encoding) { return minimum; }
    public int getState() { return STATE_INITIALIZED; }
    public int getPlaybackHeadPosition() {
        if (failPlaybackHead) throw new IllegalStateException("track_unavailable");
        if (autoPlay && playing) head = written;
        return head;
    }
    public void play() {
        playInProgress = true;
        if (playEntered != null) playEntered.countDown();
        if (continuePlay != null) {
            try {
                if (!continuePlay.await(4, TimeUnit.SECONDS)) throw new AssertionError("Playback latch timed out");
            } catch (InterruptedException error) { throw new AssertionError(error); }
        }
        restartedAfterPause = pausedDuringPlay;
        playing = true;
        playInProgress = false;
    }
    public int write(short[] samples, int offset, int count, int mode) {
        if (mode != WRITE_NON_BLOCKING) throw new AssertionError("Playback must use bounded nonblocking writes");
        writes++;
        if (failWrite || released) return -1;
        if (!acceptWrites) return 0;
        int length = transferMode == MODE_STATIC ? count : Math.min(count, 320);
        written += length;
        return length;
    }
    public void pause() { pauses++; if (playInProgress) pausedDuringPlay = true; playing = false; }
    public void flush() { flushes++; }
    public void release() { releases++; released = true; }

    public static final class Builder {
        private final AudioTrack track = new AudioTrack();
        public Builder setAudioAttributes(AudioAttributes value) { track.attributes = value; return this; }
        public Builder setAudioFormat(AudioFormat value) { track.format = value; return this; }
        public Builder setBufferSizeInBytes(int value) { track.bufferBytes = value; return this; }
        public Builder setTransferMode(int value) { track.transferMode = value; return this; }
        public AudioTrack build() {
            if (failBuild) throw new IllegalStateException("track_unavailable");
            tracks.add(track);
            return track;
        }
    }
}
