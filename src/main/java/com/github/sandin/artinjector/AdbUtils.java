package com.github.sandin.artinjector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class AdbUtils {

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

}
