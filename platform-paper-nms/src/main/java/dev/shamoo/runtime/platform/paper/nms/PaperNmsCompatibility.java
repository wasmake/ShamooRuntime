package dev.shamoo.runtime.platform.paper.nms;

import java.util.Objects;
import org.bukkit.Bukkit;

/** Exact server identity required by the mapped adapter. */
public final class PaperNmsCompatibility {
    public static final String MINECRAFT_VERSION = "1.21.8";
    public static final int PAPER_BUILD = 55;
    public static final String PACKET_MODEL_SHA256 =
            "bd1ab22deba93303194434de35d109ccb6f63c20ecd2b1e32b17bca35df87350";

    private PaperNmsCompatibility() {
    }

    public static void requireCompatibleServer() {
        requireCompatible(Bukkit.getMinecraftVersion(), Bukkit.getVersion());
    }

    static void requireCompatible(String minecraftVersion, String serverVersion) {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(serverVersion, "serverVersion");
        if (!MINECRAFT_VERSION.equals(minecraftVersion)
                || !serverVersion.matches(".*(?:Paper-|1\\.21\\.8-|build[ .])" + PAPER_BUILD
                        + "(?:[^0-9].*)?")) {
            throw new IllegalStateException("platform-paper-nms requires Paper " + MINECRAFT_VERSION
                    + " build " + PAPER_BUILD + ", found " + minecraftVersion + " / " + serverVersion);
        }
    }
}
