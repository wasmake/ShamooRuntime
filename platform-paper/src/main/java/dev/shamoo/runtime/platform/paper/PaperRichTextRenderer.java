package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.ScriptCallback;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Converts strict, data-only rich text descriptors into Adventure components. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition", "PMD.CloseResource",
        "PMD.LooseCoupling"})
public final class PaperRichTextRenderer {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_CALLBACK_USES = 1_000;
    private static final int MAX_CALLBACK_LIFETIME_SECONDS = 3_600;
    private static final Set<String> TEXT_KEYS = Set.of(
            "kind", "content", "color", "font", "bold", "italic", "underlined",
            "strikethrough", "obfuscated", "insertion", "children", "click");
    private static final Set<String> MINI_KEYS = Set.of(
            "kind", "content", "placeholders", "miniPlaceholders");
    private static final Set<String> LEGACY_KEYS = Set.of("kind", "content", "character");
    private static final Set<String> VALUE_CLICK_KEYS = Set.of("action", "value");
    private static final Set<String> CALLBACK_CLICK_KEYS = Set.of(
            "action", "callback", "uses", "lifetimeSeconds");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ActionInvoker actionInvoker;
    private final CleanupScheduler cleanupScheduler;

    public PaperRichTextRenderer(ActionInvoker actionInvoker) {
        this(actionInvoker, (delay, cleanup) -> { });
    }

    PaperRichTextRenderer(JavaPlugin plugin, ActionInvoker actionInvoker) {
        this(actionInvoker, (delay, cleanup) -> Objects.requireNonNull(plugin, "plugin").getServer()
                .getAsyncScheduler().runDelayed(plugin, ignored -> cleanup.run(),
                        delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    PaperRichTextRenderer(ActionInvoker actionInvoker, CleanupScheduler cleanupScheduler) {
        this.actionInvoker = Objects.requireNonNull(actionInvoker, "actionInvoker");
        this.cleanupScheduler = Objects.requireNonNull(cleanupScheduler, "cleanupScheduler");
    }

    public Component render(Object descriptor, Audience audience) {
        return render(descriptor, audience, ignored -> { });
    }

    Component render(Object descriptor, Audience audience, Consumer<AutoCloseable> resources) {
        return render(descriptor, Objects.requireNonNull(audience, "audience"),
                Objects.requireNonNull(resources, "resources"), 0);
    }

    private Component render(Object descriptor, Audience audience, Consumer<AutoCloseable> resources, int depth) {
        if (depth > MAX_DEPTH) {
            throw PaperDataDescriptor.invalid("rich text", "nesting exceeds " + MAX_DEPTH);
        }
        if (descriptor instanceof String text) {
            return Component.text(PaperDataDescriptor.text(text, "rich text", true));
        }
        Map<String, Object> value = PaperDataDescriptor.object(descriptor, "rich text",
                Set.of("kind", "content", "color", "font", "bold", "italic", "underlined",
                        "strikethrough", "obfuscated", "insertion", "children", "click",
                        "placeholders", "miniPlaceholders", "character"),
                Set.of("kind", "content"));
        String kind = PaperDataDescriptor.text(value.get("kind"), "rich text.kind", false);
        return switch (kind) {
            case "text" -> text(value, audience, resources, depth);
            case "mini-message" -> miniMessage(value, audience, resources, depth);
            case "legacy" -> legacy(value);
            default -> throw PaperDataDescriptor.invalid("rich text.kind", "is not supported: " + kind);
        };
    }

    private Component text(Map<String, Object> value, Audience audience,
            Consumer<AutoCloseable> resources, int depth) {
        requireExact(value, TEXT_KEYS, Set.of("kind", "content"), "text descriptor");
        TextComponent component = Component.text(PaperDataDescriptor.text(
                value.get("content"), "rich text.content", true));
        if (value.containsKey("color")) {
            component = component.color(color(PaperDataDescriptor.text(
                    value.get("color"), "rich text.color", false)));
        }
        if (value.containsKey("font")) {
            component = component.font(Key.key(PaperDataDescriptor.text(value.get("font"), "rich text.font", false)));
        }
        component = decoration(component, value, "bold", TextDecoration.BOLD);
        component = decoration(component, value, "italic", TextDecoration.ITALIC);
        component = decoration(component, value, "underlined", TextDecoration.UNDERLINED);
        component = decoration(component, value, "strikethrough", TextDecoration.STRIKETHROUGH);
        component = decoration(component, value, "obfuscated", TextDecoration.OBFUSCATED);
        if (value.containsKey("insertion")) {
            component = component.insertion(PaperDataDescriptor.text(
                    value.get("insertion"), "rich text.insertion", true));
        }
        if (value.containsKey("children")) {
            for (Object child : PaperDataDescriptor.array(value.get("children"), "rich text.children")) {
                component = component.append(render(child, audience, resources, depth + 1));
            }
        }
        if (value.containsKey("click")) {
            component = component.clickEvent(click(value.get("click"), resources));
        }
        return component;
    }

    private Component miniMessage(Map<String, Object> value, Audience audience,
            Consumer<AutoCloseable> resources, int depth) {
        requireExact(value, MINI_KEYS, Set.of("kind", "content"), "mini-message descriptor");
        String content = PaperDataDescriptor.text(value.get("content"), "rich text.content", true);
        Map<String, Object> placeholders = value.containsKey("placeholders")
                ? PaperDataDescriptor.object(value.get("placeholders"),
                        "rich text.placeholders", stringKeys(value.get("placeholders")), Set.of())
                : Map.of();
        List<TagResolver> resolvers = new ArrayList<>();
        placeholders.forEach((name, placeholder) -> {
            if (placeholder instanceof String plain) {
                resolvers.add(Placeholder.unparsed(name,
                        PaperDataDescriptor.text(plain, "rich text.placeholders." + name, true)));
            } else {
                resolvers.add(Placeholder.component(name, render(placeholder, audience, resources, depth + 1)));
            }
        });
        if (value.containsKey("miniPlaceholders")
                && PaperDataDescriptor.bool(value.get("miniPlaceholders"), "rich text.miniPlaceholders")) {
            TagResolver optional = miniPlaceholders();
            if (optional != null) {
                resolvers.add(optional);
            }
        }
        TagResolver resolver = TagResolver.resolver(resolvers);
        return MINI_MESSAGE.deserialize(content, audience, resolver);
    }

    private static Component legacy(Map<String, Object> value) {
        requireExact(value, LEGACY_KEYS, LEGACY_KEYS, "legacy descriptor");
        String content = PaperDataDescriptor.text(value.get("content"), "rich text.content", true);
        String character = PaperDataDescriptor.text(value.get("character"), "rich text.character", false);
        LegacyComponentSerializer serializer = switch (character) {
            case "&" -> LegacyComponentSerializer.legacyAmpersand();
            case "\u00a7" -> LegacyComponentSerializer.legacySection();
            default -> throw PaperDataDescriptor.invalid(
                    "rich text.character", "must be '&' or the section sign");
        };
        return serializer.deserialize(content);
    }

    private ClickEvent click(Object descriptor, Consumer<AutoCloseable> resources) {
        Map<String, Object> value = PaperDataDescriptor.object(descriptor, "rich text.click",
                Set.of("action", "value", "callback", "uses", "lifetimeSeconds"), Set.of("action"));
        String action = PaperDataDescriptor.text(value.get("action"), "rich text.click.action", false);
        if ("callback".equals(action)) {
            requireExact(value, CALLBACK_CLICK_KEYS, Set.of("action", "callback"), "callback click descriptor");
            if (!(value.get("callback") instanceof ScriptCallback callback)) {
                throw PaperDataDescriptor.invalid("rich text.click.callback", "must be a script callback");
            }
            int uses = value.containsKey("uses")
                    ? PaperDataDescriptor.integer(value.get("uses"), "rich text.click.uses") : 1;
            int lifetime = value.containsKey("lifetimeSeconds")
                    ? PaperDataDescriptor.integer(value.get("lifetimeSeconds"),
                            "rich text.click.lifetimeSeconds") : 600;
            if (uses <= 0 || uses > MAX_CALLBACK_USES) {
                throw PaperDataDescriptor.invalid("rich text.click.uses", "must be between 1 and "
                        + MAX_CALLBACK_USES);
            }
            if (lifetime <= 0 || lifetime > MAX_CALLBACK_LIFETIME_SECONDS) {
                throw PaperDataDescriptor.invalid("rich text.click.lifetimeSeconds", "must be between 1 and "
                        + MAX_CALLBACK_LIFETIME_SECONDS);
            }
            Duration duration = Duration.ofSeconds(lifetime);
            CallbackRegistration registration = new CallbackRegistration(callback, uses);
            try {
                cleanupScheduler.schedule(duration, registration::close);
                resources.accept(registration);
            } catch (RuntimeException | Error failure) {
                registration.close();
                throw failure;
            }
            return ClickEvent.callback(audience -> registration.invoke(current -> {
                if (audience instanceof CommandSender sender) {
                    actionInvoker.invoke(sender, current, Map.of("action", "click"));
                }
            }), options -> options.uses(uses).lifetime(duration));
        }
        requireExact(value, VALUE_CLICK_KEYS, VALUE_CLICK_KEYS, "click descriptor");
        if ("change-page".equals(action)) {
            return ClickEvent.changePage(PaperDataDescriptor.integer(
                    value.get("value"), "rich text.click.value"));
        }
        String target = PaperDataDescriptor.text(value.get("value"), "rich text.click.value", false);
        return switch (action) {
            case "open-url" -> ClickEvent.openUrl(target);
            case "run-command" -> ClickEvent.runCommand(target);
            case "suggest-command" -> ClickEvent.suggestCommand(target);
            case "copy-to-clipboard" -> ClickEvent.copyToClipboard(target);
            default -> throw PaperDataDescriptor.invalid("rich text.click.action", "is not supported: " + action);
        };
    }

    private static TextComponent decoration(TextComponent component, Map<String, Object> value,
            String name, TextDecoration decoration) {
        return value.containsKey(name) ? component.decoration(decoration,
                PaperDataDescriptor.bool(value.get(name), "rich text." + name)) : component;
    }

    private static TextColor color(String name) {
        TextColor color = name.startsWith("#") ? TextColor.fromHexString(name)
                : NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
        if (color == null) {
            throw PaperDataDescriptor.invalid("rich text.color", "is not a named color or RGB hex value");
        }
        return color;
    }

    private static TagResolver miniPlaceholders() {
        try {
            Class<?> type = Class.forName("io.github.miniplaceholders.api.MiniPlaceholders");
            Object resolver = type.getMethod("audienceGlobalPlaceholders").invoke(null);
            return resolver instanceof TagResolver tags ? tags : null;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException | LinkageError ignored) {
            return null;
        }
    }

    private static Set<String> stringKeys(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw PaperDataDescriptor.invalid("rich text.placeholders", "must be an object");
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        map.keySet().forEach(key -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw PaperDataDescriptor.invalid("rich text.placeholders", "keys must be non-blank text");
            }
            result.add(name);
        });
        return Set.copyOf(result);
    }

    private static void requireExact(Map<String, Object> value, Set<String> allowed,
            Set<String> required, String path) {
        PaperDataDescriptor.object(value, path, allowed, required);
    }

    @FunctionalInterface
    public interface ActionInvoker {
        void invoke(CommandSender sender, ScriptCallback callback, Map<String, Object> action);
    }

    @FunctionalInterface
    interface CleanupScheduler {
        void schedule(Duration delay, Runnable cleanup);
    }

    static final class CallbackRegistration implements AutoCloseable {
        private final AtomicReference<ScriptCallback> callback;
        private int remaining;

        CallbackRegistration(ScriptCallback callback, int uses) {
            this.callback = new AtomicReference<>(callback);
            remaining = uses;
        }

        synchronized void invoke(java.util.function.Consumer<ScriptCallback> action) {
            ScriptCallback current = callback.get();
            if (current == null || remaining == 0) {
                return;
            }
            remaining--;
            try {
                action.accept(current);
            } finally {
                if (remaining == 0) {
                    close();
                }
            }
        }

        @Override
        public synchronized void close() {
            ScriptCallback current = callback.getAndSet(null);
            if (current != null) {
                current.close();
            }
        }
    }
}
