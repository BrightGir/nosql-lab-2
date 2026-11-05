package ru.bright.couch;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

public final class DocumentRules {
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private DocumentRules() {}
    public static String time(Instant instant) { return TIME.format(instant); }
    public static void change(ObjectNode doc, String actor, String action, String state, String at) {
        doc.put("schemaVersion", 2);
        doc.put("updatedAt", at);
        doc.put("state", state);
        var change = doc.objectNode().put("by", actor).put("action", action).put("state", state).put("at", at);
        doc.set("lastModified", change.deepCopy());
        if (!doc.has("history")) doc.putArray("history");
        doc.withArray("history").add(change);
    }
}
