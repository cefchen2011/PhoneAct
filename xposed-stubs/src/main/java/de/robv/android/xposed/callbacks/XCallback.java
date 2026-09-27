package de.robv.android.xposed.callbacks;

/** Compile-time stub of de.robv.android.xposed.callbacks.XCallback (api-82). */
public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;
    public final int priority;

    public XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(XCallback other) {
        if (this == other) return 0;
        return other.priority - this.priority;
    }

    public static abstract class Param {
        public final Object[] callbacks;
        private Object extra;

        protected Param(Object[] callbacks) {
            this.callbacks = callbacks;
        }

        public Object getExtra() {
            return extra;
        }

        public void setExtra(Object extra) {
            this.extra = extra;
        }
    }
}
