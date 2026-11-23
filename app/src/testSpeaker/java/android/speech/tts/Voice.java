package android.speech.tts;

import java.util.Locale;
import java.util.Set;

public final class Voice {
    private final boolean networkRequired;

    public Voice(boolean networkRequired) {
        this.networkRequired = networkRequired;
    }

    public boolean isNetworkConnectionRequired() {
        return networkRequired;
    }

    public Locale getLocale() { return Locale.ENGLISH; }
    public Set<String> getFeatures() { return Set.of(); }
}
