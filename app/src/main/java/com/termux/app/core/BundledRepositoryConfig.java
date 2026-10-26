package com.termux.app.core;

public final class BundledRepositoryConfig {
    private BundledRepositoryConfig() {}

    public static String upgrade(String config, String previousUrl, String currentUrl) {
        String previous = "Server = " + previousUrl;
        String current = "Server = " + currentUrl;
        String[] lines = config.split("\n", -1);
        for (String line : lines) {
            if (line.trim().equals(current)) return config;
        }
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(previous)) {
                String newline = lines[i].endsWith("\r") ? "\r\n" : "\n";
                lines[i] = current + newline + lines[i];
            }
        }
        StringBuilder updated = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) updated.append('\n');
            updated.append(lines[i]);
        }
        return updated.toString();
    }
}
