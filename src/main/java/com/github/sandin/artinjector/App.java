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
        System.out.println("[Success] Android VM Injector v1.0");
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
                        .required(false)
                        .build());
        options.addOption(
                Option.builder("s")
                        .longOpt("serial")
                        .argName("serial")
                        .desc("device serial")
                        .hasArg(true)
                        .required(false)
                        .build());
        options.addOption(
                Option.builder("a")
                        .longOpt("abi")
                        .argName("abi")
                        .desc("application abi")
                        .hasArg(false)
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

        String adbPath = "adb"; //TODO
        //String adbPath = "d:\\adb\\adb.exe"; // TODO: adb path


        String packageName = cl.getOptionValue("package");
        String injectSo = cl.getOptionValue("injectso");
        String serial = cl.getOptionValue("serial");
        ArtInjector artInjector = new ArtInjector(adbPath);
        if (cl.hasOption("a")) {
            try {
                String appAbi = artInjector.getAppAbi(serial, packageName, 60 * 1000);
                System.out.println("[Success] Application abi is : " + appAbi);
            } catch (ArtInjectException e) {
                System.err.println("[Error] ErrorInfo: " + e.getMessage());
                System.exit(-1);
            } finally {
                System.exit(0);
            }
        }


        File soFile = new File(injectSo);
        if (!soFile.exists()) {
            System.out.println("[ErrorCode]: " + ErrorCodes.SOFILE_NOT_EXIST);
            System.err.println(
                    "[Error] ErrorInfo: inject so file is not exists, " + soFile.getAbsolutePath());
            return;
        }
        try {
            artInjector.inject(serial, packageName, soFile, 60 * 1000);
        } catch (ArtInjectException e) {
            System.err.println("[Error] ErrorInfo: " + e.getMessage());
            System.exit(-1);
            return;
        }
        //artInjector.dispose();
        System.out.println("[Success] Inject: OK");
        System.exit(0);
    }
}
