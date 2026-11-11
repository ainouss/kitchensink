package io.github.ainouss.kitchensink.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Step-by-step output: each step prints its name, then ok or FAILED with how long it took. */
final class Console {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private long started;

    void title(String text) {
        System.out.println();
        System.out.println("== " + text);
    }

    void step(String name) {
        started = System.currentTimeMillis();
        System.out.printf("%s  %-56s ", LocalTime.now().format(TIME), name);
        System.out.flush();
    }

    void ok(String detail) {
        long took = System.currentTimeMillis() - started;
        System.out.println("ok (" + took + " ms)" + (detail == null || detail.isEmpty() ? "" : "  " + detail));
    }

    void ok() {
        ok(null);
    }

    void failed(String why) {
        System.out.println("FAILED: " + why);
    }

    void line(String text) {
        System.out.println("   " + text);
    }

    /** Asks a yes/no question. Only "yes" is yes; with no terminal the answer is no. */
    boolean confirm(String question, boolean yes) {
        if (yes) {
            return true;
        }
        System.out.print("   " + question + " [yes/no] ");
        System.out.flush();
        try {
            String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
            return answer != null && answer.strip().equals("yes");
        } catch (IOException e) {
            return false;
        }
    }
}
