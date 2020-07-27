package com.github.sandin.artinjector;

import org.apache.commons.cli.*;

import java.io.File;

/**
 * Application
 *
 * @author san
 */
public class App {

    private static final String USAGE = "artinjector -i <injecto_so> -p <package_name>";

    public static void main(String[] args) {
        System.out.println("[✔] Android VM Injector v1.0");

        DefaultParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption(
                Option.builder("p")
                        .longOpt("package")
                        .argName("package")
                        .desc("package name")
                        .hasArg(true)
                        .required(true)
                        .build());
        options.addOption(
                Option.builder("i")
                        .longOpt("injectso")
                        .argName("injectso")
                        .desc("inject so")
                        .hasArg(true)
                        .required(true)
                        .build());
        options.addOption(
                Option.builder("s")
                        .longOpt("serial")
                        .argName("serial")
                        .desc("device serial")
                        .hasArg(true)
                        .required(false)
                        .build());
        CommandLine cl;
        try {
            cl = parser.parse(options, args);
        } catch (ParseException e) {
            HelpFormatter hf = new HelpFormatter();
            hf.printHelp(USAGE, options);
            System.exit(-1);
            return;
        }

        String packageName = cl.getOptionValue("package");
        String injectSo = cl.getOptionValue("injectso");
        String serial = cl.getOptionValue("serial");

        // String adbPath = "adb"; // TODO: adb path
        String adbPath = "d:\\tools\\android\\sdk\\platform-tools\\adb.exe"; // TODO: adb path
        File soFile = new File(injectSo);
        if (!soFile.exists()) {
            System.err.println(
                    "[❌] Error: inject so file is not exists, " + soFile.getAbsolutePath());
            return;
        }

        try {
            ArtInjector artInjector = new ArtInjector(adbPath);
            artInjector.inject(serial, packageName, soFile, 60 * 1000);
        } catch (ArtInjectException e) {
            System.err.println("[❌] Error: " + e.getMessage());
            return;
        }
        System.out.println("[✔] Inject: OK");
    }
}
