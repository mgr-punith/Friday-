package android.os;

import java.util.Comparator;
import java.util.PriorityQueue;

/** A deterministic main queue. Time advances only when a test asks it to. */
public final class Handler {
    private record Task(Handler owner, Runnable callback, Object token, long at, long order) {}
    private static final PriorityQueue<Task> TASKS = new PriorityQueue<>(
        Comparator.comparingLong(Task::at).thenComparingLong(Task::order));
    private static long sequence;

    public Handler(Looper looper) {}
    public boolean post(Runnable callback) { return postAtTime(callback, null, SystemClock.now); }
    public boolean postDelayed(Runnable callback, long delay) {
        return postAtTime(callback, null, SystemClock.now + delay);
    }
    public boolean postAtTime(Runnable callback, Object token, long at) {
        TASKS.add(new Task(this, callback, token, at, sequence++));
        return true;
    }
    public void removeCallbacks(Runnable callback) {
        TASKS.removeIf(task -> task.owner == this && task.callback == callback);
    }
    public void removeCallbacksAndMessages(Object token) {
        TASKS.removeIf(task -> task.owner == this && (token == null || task.token == token));
    }
    public static void idle() {
        int executed = 0;
        while (!TASKS.isEmpty() && TASKS.peek().at <= SystemClock.now) {
            if (++executed > 10_000) throw new AssertionError("Main queue did not settle");
            TASKS.remove().callback.run();
        }
    }
    public static void advance(long milliseconds) {
        idle();
        long target = SystemClock.now + milliseconds;
        while (!TASKS.isEmpty() && TASKS.peek().at <= target) {
            SystemClock.now = TASKS.peek().at;
            idle();
        }
        SystemClock.now = target;
        idle();
    }
    public static int pendingTasks() { return TASKS.size(); }
}
