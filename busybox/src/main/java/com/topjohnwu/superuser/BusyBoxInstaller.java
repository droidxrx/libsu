/*
 * Copyright 2021 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.topjohnwu.superuser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.Os;

import androidx.annotation.NonNull;

import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * An initializer that installs and setup the bundled BusyBox.
 * <p>
 * {@code libsu} bundles with BusyBox binaries, supporting arm/arm64/x86/x64.
 * It is non trivial to bundle executables complying with Play Store and modern Android
 * restrictions, and at the same time workaround issues on older Samsung devices.
 * Using this initializer handles all of that for you.
 * <p>
 * Register this class with {@link Shell.Builder#setInitializers(Class[])} to let {@code libsu}
 * install and setup the shell to use the bundled BusyBox binary.
 * <p>
 * After the initializer is run, the shell will be using BusyBox's "Standalone Mode ASH" mode.
 * Please refer to the
 * <a href="https://topjohnwu.github.io/Magisk/guides.html#busybox">Magisk Documentation</a>
 * for more details on how "Standalone Mode ASH" mode works.
 * @deprecated
 */
@Deprecated
public class BusyBoxInstaller extends Shell.Initializer {

    private static final String LIBSU_PREF = "libsu";
    private static final String BB_UUID = "bb_uuid";

    private static void symlink(String oldPath, String newPath) {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                Os.symlink(oldPath, newPath);
            } else {
                // Works on API 14+
                Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
                Method symlink = os.getClass().getMethod("symlink", String.class, String.class);
                symlink.invoke(os, oldPath, newPath);
            }
        } catch (Exception ignored) {}
    }

    private static Context getDeContext(Context context) {
        return Build.VERSION.SDK_INT >= 24 ? context.createDeviceProtectedStorageContext() : context;
    }

    private static boolean cmdResult(Shell shell, String cmd) {
        return shell.newJob().add(cmd).to(null).exec().isSuccess();
    }

    @Override
    public boolean onInit(@NonNull Context context, @NonNull Shell shell) {
        Shell.Job job = shell.newJob();
        job.add("export ASH_STANDALONE=1");

        File lib = new File(context.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
        if (shell.isRoot() && !cmdResult(shell, lib + " sh -c '" + lib + " true'")) {
            // This happens ONLY on some older Samsung devices
            if (cmdResult(shell, "[ -x $(magisk --path)/busybox/busybox ]")) {
                // Modern Magisk installed, use that instead
                job.add("exec $(magisk --path)/busybox/busybox sh");
            } else {
                // Copy busybox to somewhere outside of /data
                Context de = getDeContext(context);
                SharedPreferences prefs = de.getSharedPreferences(LIBSU_PREF, Context.MODE_PRIVATE);
                String uuid = prefs.getString(BB_UUID, null);
                if (uuid == null) {
                    uuid = UUID.randomUUID().toString();
                    prefs.edit().putString(BB_UUID, uuid).apply();
                }
                String tmp = "/dev/busybox-" + uuid;
                File link = new File(de.getCacheDir(), "libbusybox.so");
                if (!cmdResult(shell, "[ -x " + link + " ]")) {
                    link.delete();
                    symlink(tmp, link.getPath());
                    job.add(
                        "cp -af " + lib + " " + tmp,
                        "chmod 700 " + tmp
                    );
                }
                job.add("exec " + link + " sh");
            }
        } else {
            // We can directly execute without an issue
            job.add("exec " + lib + " sh");
        }

        return job.exec().isSuccess();
    }
}
