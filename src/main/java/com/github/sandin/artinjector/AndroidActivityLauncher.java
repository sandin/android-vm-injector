package com.github.sandin.artinjector;

public class AndroidActivityLauncher {
    public static String[] getStartActivityCommand(String activityPath) {
        return new String[]{
                "am start" +
                " -n \"" + activityPath + "\"" +
                " -a android.intent.action.MAIN" +
                " -c android.intent.category.LAUNCHER"
        };
    }

    public static String getLauncherActivityPath(String packageName, String activityName) throws ArtInjectException {
        if (packageName == null || activityName == null)
            throw new ArtInjectException("PackageName or ActivityName can't be null");
        return packageName + "/." + activityName.replace("$", "\\$");
    }
}
