package com.kieronquinn.app.simnumbersetter.services;

import android.content.Context;

import androidx.annotation.Keep;

import com.kieronquinn.app.simnumbersetter.IPrivilegedService;

public final class PrivilegedService extends IPrivilegedService.Stub {

    public PrivilegedService() {
    }

    @Keep
    public PrivilegedService(Context context) {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public int runCommand(String[] command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            byte[] buffer = new byte[1024];
            while (process.getInputStream().read(buffer) != -1) {
                // Drain output so the process cannot block on a full pipe.
            }
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
