package com.github.sandin.artinjector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public class Utils {

    public static boolean checkAdbProcess() {
        boolean flag = false;
        try {
            Process p = Runtime.getRuntime().exec("tasklist");
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


}
