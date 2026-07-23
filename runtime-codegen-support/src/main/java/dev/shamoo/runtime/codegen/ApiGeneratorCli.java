package dev.shamoo.runtime.codegen;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Command-line entry point used by Gradle sync/generation tasks. */
public final class ApiGeneratorCli {
    private static final int MINIMUM_ARGUMENTS = 5;
    private static final Set<String> PAPER_PACKAGES = Set.of(
            "org.bukkit.", "io.papermc.paper.", "com.destroystokyo.paper.", "net.kyori.adventure.");
    private static final Set<String> VELOCITY_PACKAGES = Set.of(
            "com.velocitypowered.api.", "net.kyori.adventure.");

    private ApiGeneratorCli() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < MINIMUM_ARGUMENTS) {
            throw new IllegalArgumentException(
                    "usage: <paper|velocity> <api-version> <mapping> <output-directory> <artifact>...");
        }
        String platform = arguments[0];
        Set<String> packages = switch (platform) {
            case "paper" -> PAPER_PACKAGES;
            case "velocity" -> VELOCITY_PACKAGES;
            default -> throw new IllegalArgumentException("unsupported platform: " + platform);
        };
        List<Path> artifacts = Arrays.stream(arguments, 4, arguments.length).map(Path::of).toList();
        ApiModel model = new AsmApiScanner().scan(platform, artifacts,
                name -> packages.stream().anyMatch(name::startsWith));
        new ApiDescriptorWriter().write(model, arguments[1], arguments[2], Path.of(arguments[3]), List.of());
    }
}
