package com.github.sandin.artinjector;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Client;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android VM Injector
 */
public class ArtInjector {
    private static final String[] INVOKE_LOAD_METHOD = new String[] { "java.lang.System", "load" };
    private static final String[][] BREAKPOINTS = new String[][] {
            { "android.content.ContextWrapper", "attachBaseContext" }, // for android.app.Application.attachBaseContext()
            { "android.app.Activity", "onCreate" },
            { "android.os.Looper", "myLooper" },
    };

    private final String mAdbPath;
    private AndroidDebugBridge mAndroidDebugBridge = null;

    public ArtInjector(String adbPath) {
        mAdbPath = adbPath;
    }

    /**
     * Inject a so file into target application
     *
     * @param serial device's serial, null for first device
     * @param packageName package name of application
     * @param soFile so file
     * @param timeout wait timeout
     * @return success/fail
     * @throws ArtInjectException
     */
    public boolean inject(String serial, String packageName, File soFile, long timeout) throws ArtInjectException {
        ensureAndroidDebugBridge();

        // Find device and client
        IDevice device = findDevice(serial, timeout);
        if (device == null) {
            throw new ArtInjectException("Can not find device, serial=" + serial);
        }
        Client client = device.getClient(packageName);
        if (client == null) {
            throw new ArtInjectException("Can not get client, make sure this application is debuggable, packageName=" + packageName);
        }

        // Push so file into device
        final String soRemotePath;
        try {
            soRemotePath = pushFileIntoDevice(device, packageName, soFile);
        } catch (Throwable e) {
            throw new ArtInjectException("Can not push so file into device, packageName=" + packageName + ", soFile=" + soFile);
        }

        // Attach app as JDWP Debugger
        int port = client.getDebuggerListenPort();
        final ArtDebugger artDebugger = new ArtDebugger();
        boolean attached = artDebugger.attach("localhost", port, timeout);
        if (!attached) {
            throw new ArtInjectException("Can not attach to this app, packageName=" + packageName + ", host=localhost, port=" + port);
        }

        // Create breakpoints
        for (String[] breakpoint : BREAKPOINTS) {
            artDebugger.addBreakpoint(new ArtDebugger.Breakpoint.Builder().className(breakpoint[0]).methodName(breakpoint[1]).build());
        }

        // Inject java code to load so
        CountDownLatch latch = new CountDownLatch(1); // wait for breakpoint hint
        final ArtDebugger.EvaluateResult[] future = {null};
        artDebugger.registerEventListener(new ArtDebugger.EventListener() {
            public boolean onEvent(ArtDebugger.Event event) {
                if (event instanceof ArtDebugger.BreakpointEvent && future[0] == null) {
                    future[0] = artDebugger.evaluateMethod(event.getEvaluateContext(),
                            INVOKE_LOAD_METHOD[0], INVOKE_LOAD_METHOD[1], new Object[] { soRemotePath });
                    latch.countDown();
                    return true;
                }
                return false;
            }
        });
        try {
            latch.wait(timeout);
        } catch (InterruptedException ignore) {
        }
        artDebugger.dispose();

        // Get the result
        ArtDebugger.EvaluateResult result = future[0];
        if (result == null) {
            throw new ArtInjectException("Breakpoint timeout, breakpoints=" + artDebugger.getBreakpoints());
        }
        if (result.getError() != null) {
            throw new ArtInjectException("Evaluate java code throw exception, error=" + result.getError());
        }
        return true;
    }

    private void ensureAndroidDebugBridge() throws ArtInjectException {
        if (mAndroidDebugBridge == null) {
            try {
                AndroidDebugBridge.init(true, false, ImmutableMap.of());
                mAndroidDebugBridge = AndroidDebugBridge.createBridge(mAdbPath, false);
            } catch (Throwable e) {
                throw new ArtInjectException("Can not create AndroidDebugBridget", e);
            }
        }
    }

    private IDevice findDevice(String serial, long timeout) throws ArtInjectException {
        ensureAndroidDebugBridge();
        IDevice targetDevice = null;

        long startTime = System.currentTimeMillis();
        while (true) {
            if (System.currentTimeMillis() - startTime > timeout) {
                break;
            }

            if (mAndroidDebugBridge.hasInitialDeviceList()) {
                IDevice[] devices = mAndroidDebugBridge.getDevices();
                if (devices.length > 0) {
                    for (IDevice device : devices) {
                        if (serial == null || serial.equals(device.getSerialNumber())) {
                            targetDevice = device;
                            break;
                        }
                    }
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ignore) {
            }
        }

        return targetDevice;
    }

    private static String adbShell(IDevice device, String[] command) {
        CountDownLatch latch = new CountDownLatch(1);
        CollectingOutputReceiver receiver = new CollectingOutputReceiver(latch);

        try {
            String cmd = String.join(" ", command);
            System.out.println("adb shell " + cmd);
            device.executeShellCommand(cmd, receiver, 2L, TimeUnit.SECONDS);
        } catch (Exception var6) {
            return null;
        }

        try {
            latch.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException var5) {
            return null;
        }

        return receiver.getOutput().trim();
    }

    private String pushFileIntoDevice(IDevice device, String packageName, File localFile) throws Exception {
        String filename = localFile.getName();
        String remoteDir = "/data/data/" + packageName + "/";
        String remotePath = remoteDir + filename;

        boolean rooted = device.isRoot();
        if (!rooted) {
            rooted = device.root(); // try to get root permission
        }

        device.pushFile(localFile.getAbsolutePath(), "/data/local/tmp/" + filename);
        if (rooted) {
            adbShell(device, new String[]{"chmod", "777", remotePath});

            String[] cmd = new String[]{"cp", "/data/local/tmp/" + filename, remotePath};
            String out = adbShell(device, cmd);
            if (out.trim().length() > 0) {
                throw new Exception(out.trim()); // error
            }
        } else {
            String[] cmd = new String[]{"run-as", packageName, "cp", "/data/local/tmp/" + filename, remotePath};
            String out = adbShell(device, cmd);
            if (out.trim().length() > 0) {
                throw new Exception(out.trim()); // error
            }
        }
        return remotePath;
    }

}
