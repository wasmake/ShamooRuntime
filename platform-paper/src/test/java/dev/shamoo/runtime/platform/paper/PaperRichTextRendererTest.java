package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts"})
class PaperRichTextRendererTest {
    private final PaperRichTextRenderer renderer = new PaperRichTextRenderer((sender, callback, action) -> { });

    @Test
    void rendersTextChildrenColorAndLegacyFormats() {
        Component text = renderer.render(Map.of(
                "kind", "text",
                "content", "Hello ",
                "color", "#55ff55",
                "bold", true,
                "children", List.of("world")), Audience.empty());
        Component legacy = renderer.render(Map.of(
                "kind", "legacy", "content", "&aGreen", "character", "&"), Audience.empty());

        assertEquals("Hello world", PlainTextComponentSerializer.plainText().serialize(text));
        assertEquals(NamedTextColor.GREEN, text.color());
        assertEquals(TextDecoration.State.TRUE, text.decoration(TextDecoration.BOLD));
        assertEquals("Green", PlainTextComponentSerializer.plainText().serialize(legacy));
    }

    @Test
    void miniMessagePlainPlaceholdersAreNeverParsedAsMarkup() {
        Component component = renderer.render(Map.of(
                "kind", "mini-message",
                "content", "Value: <value>",
                "placeholders", Map.of("value", "<red>unsafe</red>"),
                "miniPlaceholders", false), Audience.empty());

        assertEquals("Value: <red>unsafe</red>",
                PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    void acceptsOmittedMiniMessageOptions() {
        Component component = renderer.render(Map.of(
                "kind", "mini-message", "content", "<green>Ready"), Audience.empty());

        assertEquals("Ready", PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    void appliesCallbackDefaultsWhenBoundsAreOmitted() {
        dev.shamoo.runtime.core.ScriptCallback callback = values -> CompletableFuture.completedFuture(null);

        assertDoesNotThrow(() -> renderer.render(Map.of(
                "kind", "text", "content", "Click", "click",
                Map.of("action", "callback", "callback", callback)), Audience.empty()));
    }

    @Test
    void rejectsUnknownKeysAndUnsupportedLegacyCharacters() {
        assertThrows(IllegalArgumentException.class, () -> renderer.render(Map.of(
                "kind", "text", "content", "value", "unknown", true), Audience.empty()));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(Map.of(
                "kind", "legacy", "content", "value", "character", "%"), Audience.empty()));
    }

    @Test
    void callbackRegistrationsCloseAfterUsesAndScheduledLifetime() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        dev.shamoo.runtime.core.ScriptCallback callback = new dev.shamoo.runtime.core.ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        PaperRichTextRenderer.CallbackRegistration registration =
                new PaperRichTextRenderer.CallbackRegistration(callback, 2);
        registration.invoke(current -> current.invoke(List.of()));
        registration.invoke(current -> current.invoke(List.of()));
        registration.invoke(current -> current.invoke(List.of()));
        assertEquals(2, invocations.get());
        assertEquals(1, closes.get());

        AtomicReference<Runnable> lifetimeCleanup = new AtomicReference<>();
        dev.shamoo.runtime.core.ScriptCallback expiring = new dev.shamoo.runtime.core.ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        PaperRichTextRenderer scheduled = new PaperRichTextRenderer((sender, current, action) -> { },
                (delay, cleanup) -> lifetimeCleanup.set(cleanup));
        scheduled.render(Map.of("kind", "text", "content", "Click", "click",
                Map.of("action", "callback", "callback", expiring, "uses", 3,
                        "lifetimeSeconds", 30)), Audience.empty());
        lifetimeCleanup.get().run();
        assertEquals(2, closes.get());
    }
}
