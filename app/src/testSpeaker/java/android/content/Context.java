package android.content;

/** Platform operations used by the production voice lifecycle classes. */
public class Context {
    public android.media.AudioManager audioManager = new android.media.AudioManager();
    public Context getApplicationContext() { return this; }
    public <T> T getSystemService(Class<T> type) { return type.cast(audioManager); }
}
