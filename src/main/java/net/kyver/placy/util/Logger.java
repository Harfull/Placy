package net.kyver.placy.util;

import net.kyver.placy.config.EnvironmentSetup;

public class Logger {
    private static final String PREFIX = Colors.BOLD + Colors.CYAN + "[Placy]" + Colors.RESET;

    private static boolean isDebugEnabled() {
        return EnvironmentSetup.isDebugModeEnabled();
    }

    public static void info(String message) {
        System.out.println(PREFIX + " " + Colors.GREEN + "INFO" + Colors.RESET + " " + message);
    }

    public static void warn(String message) {
        System.out.println(PREFIX + " " + Colors.YELLOW + "WARN" + Colors.RESET + " " + message);
    }

    public static void error(String message) {
        System.err.println(PREFIX + " " + Colors.RED + "ERROR" + Colors.RESET + " " + message);
    }

    public static void error(String message, Throwable throwable) {
        System.err.println(PREFIX + " " + Colors.RED + "ERROR" + Colors.RESET + " " + message);
        throwable.printStackTrace(System.err);
    }

    public static void success(String message) {
        System.out.println(PREFIX + " " + Colors.GREEN + "SUCCESS" + Colors.RESET + " " + message);
    }

    public static void debug(String message) {
        if (isDebugEnabled()) {
            System.out.println(PREFIX + " " + Colors.BLUE + "DEBUG" + Colors.RESET + " " + message);
        }
    }
}
