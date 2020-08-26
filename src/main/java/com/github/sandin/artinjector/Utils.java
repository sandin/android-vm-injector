package com.github.sandin.artinjector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Properties;

public class Utils {

    public static String getOsName() {
        Properties prop = System.getProperties();
        return prop.getProperty("os.name");
    }

    public static boolean checkAdbProcess(String os) {
        boolean flag = false;
        String checkCommand;
        if (os.contains("Windows")) {
            checkCommand = "tasklist";
        } else {
            checkCommand = "top | grep adb";
        }
        try {
            Process p = Runtime.getRuntime().exec(checkCommand);
            BufferedReader bw = new BufferedReader(new InputStreamReader(p
                    .getInputStream()));
            String str = "";
            StringBuffer sb = new StringBuffer();
            while (true) {
                str = bw.readLine();
                if (str != null) {
                    sb.append(str.toLowerCase());
                } else {
                    break;
                }
            }
            String adb = "adb.exe";
            if (sb.toString().contains(adb)) {
                flag = true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return flag;
    }

    public static Boolean checkSoPaths(String[] soPaths) {
        for (String soPath : soPaths) {
            File soFile = new File(soPath);
            if (!soFile.exists()) {
                System.out.println("[ErrorCode]: " + ErrorCodes.SOFILE_NOT_EXIST);
                System.err.println(
                        "[Error] ErrorInfo: inject so file is not exists, " + soFile.getAbsolutePath());
                return false;
            }
        }
        return true;
    }


    private static void bindPort(String host, int port) throws Exception {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(host, port));
        s.close();
    }

    public static boolean checkPort(int port) {
        try {
            bindPort("0.0.0.0", port);
            bindPort(InetAddress.getLocalHost().getHostAddress(), port);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
