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
        DefaultParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption(Option.builder("p").argName("package").desc("package name").required(true).build());
        options.addOption(Option.builder("i").argName("injectso").desc("inject so").required(true).build());
        options.addOption(Option.builder("s").argName("serial").desc("device serial").required(false).build());
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
        String injectSo = cl.getOptionValue("injectSo");
        String serial = cl.getOptionValue("serial");

        String adbPath = "adb"; // TODO: adb path
        File soFile = new File(injectSo);

        try {
            ArtInjector artInjector = new ArtInjector(adbPath);
            artInjector.inject(serial, packageName, soFile, 10 * 1000);
        } catch (ArtInjectException e) {
            System.err.println("Error: " + e.getMessage());
        }
        System.out.println("Inject: OK");
    }
}
