package android.content;

/** Platform operations used by the production voice lifecycle classes. */
public class Context {
    public android.media.AudioManager audioManager = new android.media.AudioManager();
    public android.content.pm.PackageManager packageManager = new android.content.pm.PackageManager();
    public Context getApplicationContext() { return this; }
    public android.content.pm.PackageManager getPackageManager() { return packageManager; }
    public <T> T getSystemService(Class<T> type) { return type.cast(audioManager); }
}
