package com.NzothR.apm.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * APM configuration.
 */
public final class APMConfig {

    public static boolean enabled = true;
    public static boolean debugLog = true;

    private APMConfig() {}

    public static void load(File configFile) {
        Configuration config = new Configuration(configFile);
        try {
            config.load();

            enabled = config.getBoolean(
                "enabled",
                Configuration.CATEGORY_GENERAL,
                true,
                "Enable automatic pattern doubling when Advanced Pattern Matrix is connected");

            debugLog = config.getBoolean(
                "debugLog",
                Configuration.CATEGORY_GENERAL,
                false,
                "Enable detailed debug logging for pattern doubling operations");

        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }
}
