package android.content.pm;

import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

public final class PackageManager {
    public static final int MATCH_DEFAULT_ONLY = 65536;
    public final List<ResolveInfo> services = new ArrayList<>();
    public int lastFlags = -1;
    public String lastAction;
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        lastFlags = flags;
        lastAction = intent.action;
        // A MediaBrowserService does not need CATEGORY_DEFAULT in its intent filter.
        return (flags & MATCH_DEFAULT_ONLY) != 0 ? List.of() : services;
    }
}
