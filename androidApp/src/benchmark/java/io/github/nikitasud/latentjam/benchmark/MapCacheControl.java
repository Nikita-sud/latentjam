// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.benchmark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;

/** Shell-only fixture control, absent from every shipping variant. */
public final class MapCacheControl extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getPackageName().endsWith(".benchmark")) throw new SecurityException();
        File map = new File(context.getFilesDir(), "map_layout.bin");
        if ("io.github.nikitasud.latentjam.benchmark.CLEAR_MAP".equals(intent.getAction())) {
            File temporary = new File(context.getFilesDir(), "map_layout.bin.tmp");
            if ((map.exists() && !map.delete()) || (temporary.exists() && !temporary.delete())) {
                setResultCode(2);
                return;
            }
        }
        setResultCode(map.isFile() ? 1 : 0);
    }
}
