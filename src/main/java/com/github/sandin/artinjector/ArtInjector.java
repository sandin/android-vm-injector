package com.github.sandin.artinjector;

import com.android.ddmlib.*;
import com.google.common.collect.ImmutableMap;
import com.sun.jdi.ThreadReference;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android VM Injector
 */
public class ArtInjector {
    private static final String[] INVOKE_LOAD_METHOD = new String[]{"java.lang.System", "load"};
    private final String mAdbPath;
    private AndroidDebugBridge mAndroidDebugBridge = null;

    public ArtInjector(String adbPath) {
        mAdbPath = adbPath;
    }

    public ArtInjector() {
        mAdbPath = null;
    }

    private static String adbShell(IDevice device, String[] command) {
        CountDownLatch latch = new CountDownLatch(1);
        CollectingOutputReceiver receiver = new CollectingOutputReceiver(latch);
        try {
            String cmd = String.join(" ", command);
            System.out.println("[Success] adb shell " + cmd);
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

    //Launch Application
    public void launchApplication(String serial, String packageName, String activityName, long timeout) throws ArtInjectException {

        IDevice device = getDevice(serial, timeout);

        String[] launchAppCommand = new String[]{
                "am set-debug-app -w " + packageName
        };
        adbShell(device, launchAppCommand);
        if (activityName != null) {
            String activityPath = AndroidActivityLauncher.getLauncherActivityPath(packageName, activityName);
            String[] startActivityCommand = AndroidActivityLauncher.getStartActivityCommand(activityPath);
            adbShell(device, startActivityCommand);
        } else {
            String[] startActivityCommand = new String[]{
                    "monkey", "-p", packageName, "-c android.intent.category.LAUNCHER --wait-dbg 1 2>&1 | sed \"s/^/\\[**\\] monkey says: /\""
            };
            adbShell(device, startActivityCommand);
        }
        //String[] clearDebugAppCommand = new String[]{
        //        "am clear-debug-app"
        //};
        //adbShell(device, clearDebugAppCommand);
    }

    public IDevice getDevice(String serial, Long timeout) throws ArtInjectException {
        ensureAndroidDebugBridge();

        // Find device and client
        IDevice device = findDevice(serial, timeout);
        if (device == null) {
            System.out.println("[ErrorCode]: " + ErrorCodes.CANT_FIND_DEVICE);
            throw new ArtInjectException("Can not find device, serial=" + serial);
        }
        System.out.println("[Success] found device, serial=" + device.getSerialNumber());
        return device;
    }

    /**
     * Inject a so file into target application
     *
     * @param serial      device's serial, null for first device
     * @param packageName package name of application
     * @param soFiles     so files
     * @param timeout     wait timeout
     * @throws ArtInjectException
     */
    public void inject(String serial, String packageName, File[] soFiles, String breakPoints, long timeout)
            throws ArtInjectException {

        IDevice device = getDevice(serial, timeout);

        //TODO root
        Client client = findClient(device, packageName, timeout);
        if (client == null) {
            System.out.println("[ErrorCode]: " + ErrorCodes.CANT_GET_CLIENT);
            throw new ArtInjectException(
                    "Can not get client, make sure this application is debuggable and is running, packageName="
                            + packageName);
        }

        String appAbi = client.getClientData().getAbi();
        System.out.println(
                "[Success] found app, packageName="
                        + client.getClientData().getPackageName()
                        + ", pid="
                        + client.getClientData().getPid()
                        + ", abi="
                        + appAbi);

        // Push so file into device
        String[] soRemotePaths = new String[soFiles.length];
        for (int i = 0; i < soFiles.length; i++) {
            File soFile = soFiles[i];
            String soRemotePath;
            try {
                soRemotePath = pushFileIntoDevice(device, packageName, soFile);
                soRemotePaths[i] = soRemotePath;
            } catch (Throwable e) {
                System.out.println("[ErrorCode]: " + ErrorCodes.CANT_PUSH_FILE);
                throw new ArtInjectException(
                        "Can not push so file into device, packageName="
                                + packageName
                                + ", soFile="
                                + soFile);
            }
            System.out.println(
                    "[Success] pushed so file into device, local file: "
                            + soFile.getAbsolutePath()
                            + ", remote file: "
                            + soRemotePath);
        }

        //check abi
        boolean checkResult = checkAbi(device, appAbi, soRemotePaths);
        if (!checkResult) {
            throw new ArtInjectException("The architecture of the application and so does not match");
        }

        // Attach app as JDWP Debugger
        int port = client.getDebuggerListenPort();
        final ArtDebugger artDebugger = new ArtDebugger();
        boolean attached = artDebugger.attach("localhost", port, timeout);
        if (!attached) {
            System.out.println("[ErrorCode]: " + ErrorCodes.CANT_ATTACH_APP);
            throw new ArtInjectException(
                    "Can not attach to this app, packageName="
                            + packageName
                            + ", host=localhost, port="
                            + port);
        }
        System.out.println(
                "[Success] attached app as jdwp debugger, port="
                        + port
                        + ", vm="
                        + artDebugger.getVirtualMachine().name()
                        + ", jdwp version="
                        + artDebugger.getVirtualMachine().version());

        String[][] BREAKPOINTS;
        if (breakPoints == null) {
            BREAKPOINTS = new String[][]{
                    {
                            "android.content.ContextWrapper", "attachBaseContext"
                    }, // for android.app.Application.attachBaseContext()
                    {"android.app.Activity", "onCreate"},
                    {"android.os.Looper", "myLooper"},
                    {"com.unity3d.player.UnityPlayer", "executeGLThreadJobs"},
            };
        } else {
            String[] breakPoint = breakPoints.split(",");
            BREAKPOINTS = new String[breakPoint.length][2];
            for (int i = 0; i < breakPoint.length; i++) {
                String point = breakPoint[i];
                int index = point.lastIndexOf(".");
                if (index == -1) {
                    System.out.println("[ErrorCode]: " + ErrorCodes.BREAKPOINTS_HAVE_ERROR);
                    throw new ArtInjectException("Breakpoint format error");
                }
                String className = point.substring(0, index);
                String methodName = point.substring(index + 1);
                BREAKPOINTS[i][0] = className;
                BREAKPOINTS[i][1] = methodName;
            }
        }
        // Create breakpoints
        for (String[] breakpoint : BREAKPOINTS) {
            if (artDebugger.addBreakpoint(
                    new ArtDebugger.Breakpoint.Builder()
                            .className(breakpoint[0])
                            .methodName(breakpoint[1])
                            .build())) {
                System.out.println("[Success] added breakpoint: " + breakpoint[0] + "." + breakpoint[1]);
            }

        }

        // Inject java code to load so
        CountDownLatch latch = new CountDownLatch(1); // wait for breakpoint hint
        final ArtDebugger.EvaluateResult[] future = {null};
        artDebugger.registerEventListener(
                event -> {
                    ThreadReference thread = event.getEvaluateContext().getThread();
                    if (event instanceof ArtDebugger.BreakpointEvent
                            && future[0] == null
                            && "main".equals(thread.name())) {
                        for (String soRemotePath : soRemotePaths) {
                            future[0] =
                                    artDebugger.evaluateMethod(
                                            event.getEvaluateContext(),
                                            INVOKE_LOAD_METHOD[0],
                                            INVOKE_LOAD_METHOD[1],
                                            new String[]{soRemotePath});
                        }
                        latch.countDown();
                        return true;
                    }
                    return false;
                });
        try {
            System.out.println("[Success] waiting for breakpoints");
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
        }
        artDebugger.dispose();

        // Get the result
        ArtDebugger.EvaluateResult result = future[0];
        if (result == null) {
            System.out.println("[ErrorCode]: " + ErrorCodes.BREAKPOINT_TIMEOUT);
            throw new ArtInjectException(
                    "Breakpoint timeout, breakpoints=" + artDebugger.getBreakpoints());
        }
        if (result.getError() != null) {
            String errorMessage = result.getError();
            String tips = errorMessage.substring(errorMessage.lastIndexOf(" ") + 1, errorMessage.length() - 1);
            if (tips.equals("32-bit"))
                System.out.println("[ErrorCode]: " + ErrorCodes.SOFILE_SHOULD_USE_32BIT);
            else if (tips.equals("64-bit"))
                System.out.println("[ErrorCode]: " + ErrorCodes.SOFILE_SHOULD_USE_64BIT);
            throw new ArtInjectException(
                    "Evaluate java code throw exception, error=" + result.getError());
        }
    }

    private void ensureAndroidDebugBridge() throws ArtInjectException {
        if (mAndroidDebugBridge == null) {
            try {

                //check port
                int debugPort = 9700;
                while (!Utils.checkPort(debugPort)) {
                    debugPort++;
                }

                DdmPreferences.setDebugPortBase(9600);
                DdmPreferences.setSelectedDebugPort(debugPort);
                System.out.println("[Success] UsePort is : " + debugPort);
                AndroidDebugBridge.disconnectBridge();
                AndroidDebugBridge.terminate();
                AndroidDebugBridge.init(true, false, ImmutableMap.of());
                if (mAdbPath != null) {
                    mAndroidDebugBridge = AndroidDebugBridge.createBridge(mAdbPath, false);
                } else {
                    AndroidDebugBridge.addDeviceChangeListener(new AndroidDebugBridge.IDeviceChangeListener() {
                        @Override
                        public void deviceConnected(IDevice device) {
                            System.out.println("[Success] Device Connected , Serial is : " + device.getSerialNumber());
                        }

                        @Override
                        public void deviceDisconnected(IDevice device) {
                            System.out.println("[Success] Device Disconnected , Serial is : " + device.getSerialNumber());
                        }

                        @Override
                        public void deviceChanged(IDevice device, int changeMask) {
                            System.out.println("[Success] Device Changed , Serial is : " + device.getSerialNumber());
                        }
                    });
                    mAndroidDebugBridge = AndroidDebugBridge.createBridge();
                    Thread.sleep(1000);
                }
                while (!mAndroidDebugBridge.isConnected()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(200);
                    } catch (InterruptedException e) {
                        // if cancelled, don't wait for connection and return immediately
                        throw new ArtInjectException("Timed out attempting to connect to adb: " + mAdbPath);
                    }
                }
            } catch (Throwable e) {
                System.out.println("[ErrorCode]: " + ErrorCodes.CANT_GET_ADB);
                System.out.println(mAdbPath);
                throw new ArtInjectException("Can not create AndroidDebugBridget", e);
            }
        }
    }

    public IDevice findDevice(String serial, long timeout) throws ArtInjectException {
        ensureAndroidDebugBridge();
        IDevice targetDevice = null;

        long startTime = System.currentTimeMillis();
        while (targetDevice == null) {
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

    private Client findClient(IDevice device, String packageName, long timeout) {
        Client client = device.getClient(packageName);
        long startTime = System.currentTimeMillis();
        while (client == null) {
            if (System.currentTimeMillis() - startTime > timeout) {
                break;
            }
            Client[] clients = device.getClients();
            System.out.println("---------------- " + clients.length);
            for (Client c : clients) {
                System.out.println(
                        "[Success] debuggable app, packageName: "
                                + c.getClientData().getPackageName()
                                + ", debugger port: "
                                + c.getDebuggerListenPort());
                if (packageName.equals(c.getClientData().getClientDescription())) {
                    client = c;
                    break;
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {

            }
        }
        return client;
    }

    private String pushFileIntoDevice(IDevice device, String packageName, File localFile)
            throws Exception {
        String filename = localFile.getName();
        String remoteDir = "/data/data/" + packageName + "/";
        String remotePath = remoteDir + filename;

        device.pushFile(localFile.getAbsolutePath(), "/data/local/tmp/" + filename);

        //adbShell(device, new String[]{"run-as", packageName, "mkdir", remoteDir});
        String[] cmd =
                new String[]{
                        "run-as", packageName, "cp", "/data/local/tmp/" + filename, remotePath
                };
        String out = adbShell(device, cmd);
        if (out.trim().length() > 0) {
            // try again as root
            boolean rooted = device.isRoot();
            if (!rooted) {
                rooted = device.root(); // try to get root permission
            }
            if (rooted) {
                adbShell(device, new String[]{"setenforce", "0"});
                out = adbShell(device, new String[]{"cp", "/data/local/tmp/" + filename, remotePath});
                if (out.trim().length() > 0) {
                    throw new Exception(out.trim()); // error
                }
                adbShell(device, new String[]{"chmod", "777", remotePath});
            } else {
                throw new Exception(out.trim()); // error
            }
        }
        return remotePath;
    }


    public void dispose() {
        AndroidDebugBridge.disconnectBridge();
        AndroidDebugBridge.terminate();
    }

    public String getAppAbi(String serial, String packageName, long timeout) throws ArtInjectException {
        IDevice device = getDevice(serial, timeout);
        Client client = findClient(device, packageName, timeout);
        if (client == null) {
            System.out.println("[ErrorCode]: " + ErrorCodes.CANT_GET_CLIENT);
            throw new ArtInjectException(
                    "Can not get client, make sure this application is debuggable and is running, packageName="
                            + packageName);
        }
        return client.getClientData().getAbi();
    }


    private boolean checkAbi(IDevice device, String appAbi, String[] soRemotePaths) {
        for (String soRemotePath : soRemotePaths) {
            String checkResult = adbShell(device, new String[]{"file " + soRemotePath});
            int soAbiIndex = checkResult.indexOf("bit");
            if (soAbiIndex != -1) {
                String soAbi = checkResult.substring(soAbiIndex - 3, soAbiIndex - 1);
                String clientAbi = appAbi.substring(0, 2);
                String fileName = soRemotePath.substring(soRemotePath.lastIndexOf("/") + 1);
                if (!soAbi.equals(clientAbi)) {
                    System.out.println("[ErrorInfo]: " + fileName + " is " + soAbi + "-bit" + " but the application is " + clientAbi + "-bit");
                    return false;
                }
            }
        }
        return true;
    }

}
