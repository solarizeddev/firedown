package com.solarized.firedown.data;

import com.solarized.firedown.manager.ServiceActions;

/**
 * Type-safe event hierarchy replacing the raw {@link android.os.Message} event bus.
 * <p>
 * Each subclass carries exactly the data the UI layer needs — no more
 * {@code msg.arg1} / {@code msg.arg2} / {@code (int) msg.obj} casts.
 */
public abstract class TaskEvent {

    private TaskEvent() {} // sealed — only inner classes can extend

    /** A long-running operation (move/encrypt/etc.) has started. */
    public static final class Started extends TaskEvent {
        private final ServiceActions action;

        public Started(ServiceActions action) {
            this.action = action;
        }

        public ServiceActions getAction() {
            return action;
        }
    }

    /** Progress update for an ongoing operation (0–100). */
    public static final class Progress extends TaskEvent {
        private final int percent;

        public Progress(int percent) {
            this.percent = percent;
        }

        public int getPercent() {
            return percent;
        }
    }

    /** A long-running operation completed successfully. */
    public static final class Finished extends TaskEvent {
        private final ServiceActions action;
        private final Object result;

        public Finished(ServiceActions action, Object result) {
            this.action = action;
            this.result = result;
        }

        public ServiceActions getAction() {
            return action;
        }

        public Object getResult() {
            return result;
        }
    }

    /** File(s) deleted. {@code count} is the number of items removed. */
    public static final class Deleted extends TaskEvent {
        private final int count;

        public Deleted(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }

    /** An error occurred. {@code count} is the number of failed items. */
    public static final class Error extends TaskEvent {
        private final int count;

        public Error(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }

    /** The user cancelled an ongoing operation. */
    public static final class Cancelled extends TaskEvent {
        public Cancelled() {}
    }

}