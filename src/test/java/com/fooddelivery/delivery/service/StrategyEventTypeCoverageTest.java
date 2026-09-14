package com.fooddelivery.delivery.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every event type a strategy declares must have a typed class in the listener's EVENT_CLASSES map.
 *
 * <p>Six did not, and the failure was silent. The listener fell back to handing the strategy the raw
 * {@code JsonNode}; the strategies dispatch on {@code instanceof}, so no branch matched, no
 * exception was thrown, and ORDER_PREPARING, ORDER_READY, DRIVER_ASSIGNED, DISPATCH_FAILED,
 * MANUAL_INTERVENTION_REQUIRED and ORDER_DELAY_REJECTED were dropped on the floor -- driver
 * assignment and dispatch-failure handling stopped working with nothing in the logs.
 *
 * <p>Source-level rather than reflective on purpose: the map is a private static initialiser and the
 * strategy list is assembled by Spring at runtime, so reading the source is what actually catches
 * the two drifting apart at edit time.
 */
public class StrategyEventTypeCoverageTest {

    private static final Path SERVICE_DIR =
            Paths.get("src/main/java/com/fooddelivery/delivery/service");

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    @Test
    public void everyStrategyEventTypeHasATypedClass() throws IOException {
        String consumer = stripComments(
                Files.readString(SERVICE_DIR.resolve("OrderEventConsumer.java")));

        Set<String> mapped = new HashSet<>();
        Matcher m = Pattern.compile("EVENT_CLASSES\\.put\\([\\w.]*EventType\\.([A-Z_]+)")
                .matcher(consumer);
        while (m.find()) {
            mapped.add(m.group(1));
        }
        assertTrue(mapped.size() >= 12,
                "found only " + mapped.size() + " EVENT_CLASSES entries -- the parse broke, "
                        + "not the code. Fix this test before trusting it.");

        List<String> gaps = new ArrayList<>();
        try (Stream<Path> files = Files.list(SERVICE_DIR.resolve("strategy"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(p));
                Matcher body = Pattern.compile(
                        "getEventTypes\\(\\)\\s*\\{(.*?)\\n\\s{0,4}\\}", Pattern.DOTALL).matcher(src);
                if (!body.find()) {
                    continue;
                }
                Set<String> declared = new HashSet<>();
                Matcher viaEnum = Pattern.compile("EventType\\.([A-Z_]+)").matcher(body.group(1));
                while (viaEnum.find()) {
                    declared.add(viaEnum.group(1));
                }
                Matcher viaLiteral = Pattern.compile("\"([A-Z_]{4,})\"").matcher(body.group(1));
                while (viaLiteral.find()) {
                    declared.add(viaLiteral.group(1));
                }
                for (String d : declared) {
                    if (!mapped.contains(d)) {
                        gaps.add(p.getFileName() + " handles " + d + " but EVENT_CLASSES has no class for it");
                    }
                }
            }
        }

        assertTrue(gaps.isEmpty(),
                "A strategy handles an event the listener cannot bind, so the event is dropped "
                        + "silently:\n  " + String.join("\n  ", gaps));
    }
}
