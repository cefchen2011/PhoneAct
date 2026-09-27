package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/** Compile-time stub of de.robv.android.xposed.callbacks.XC_LoadPackage (api-82). */
public abstract class XC_LoadPackage extends XCallback {

    public XC_LoadPackage() {
        super();
    }

    public XC_LoadPackage(int priority) {
        super(priority);
    }

    public void call(LoadPackageParam param) {
        throw new UnsupportedOperationException("stub");
    }

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;

        public LoadPackageParam(Object[] callbacks) {
            super(callbacks);
        }
    }
}
