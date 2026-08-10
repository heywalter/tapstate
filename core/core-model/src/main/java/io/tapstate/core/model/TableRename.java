package io.tapstate.core.model;

import java.util.Locale;
import java.util.Map;

/**
 * The target name a {@link RenameSpec} gives one source table. Priority is explicit map, then the bulk
 * rules, then the source name unchanged; the bulk rules apply the case transform to the source name first
 * and append the literal prefix and suffix after, so neither affix is itself case-folded.
 *
 * <p>This lives beside the spec rather than at either use site because both sides must agree exactly: the
 * validate layer rejects a workspace by the names it computes here, and the write side creates tables by
 * them. Two implementations would let a workspace validate against one set of names and write to another.
 *
 * <p>The compound cases segment on any non letter-or-digit and on case boundaries, so {@code order_items},
 * {@code orderItems} and {@code ORDER_ITEMS} all reach the same words. A character that is neither a letter
 * nor a digit is a separator and does not survive the transform - the case a separator-only name leaves is
 * an empty name, which the validate layer is what rejects.
 */
public final class TableRename {

    private TableRename() {
    }

    /** The target name for {@code sourceName}, or {@code sourceName} itself when no rule reaches it. */
    public static String apply(String sourceName, RenameSpec rename) {
        if (rename == null) {
            return sourceName;
        }
        Map<String, String> explicit = rename.map();
        if (explicit != null && explicit.containsKey(sourceName)) {
            return explicit.get(sourceName);
        }
        RenameCase caseMode = rename.caseMode();
        String transformed = caseMode == null ? sourceName : switch (caseMode) {
            case UPPER -> sourceName.toUpperCase(Locale.ROOT);
            case LOWER -> sourceName.toLowerCase(Locale.ROOT);
            case CAMEL -> compoundCase(sourceName, false);
            case PASCAL -> compoundCase(sourceName, true);
        };
        return (rename.prefix() == null ? "" : rename.prefix())
                + transformed
                + (rename.suffix() == null ? "" : rename.suffix());
    }

    private static String compoundCase(String name, boolean capitalizeFirst) {
        StringBuilder result = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                appendWord(result, word, capitalizeFirst);
                continue;
            }
            boolean lowerOrDigitFollowedByUpper = index > 0
                    && isLowerOrDigit(name.charAt(index - 1))
                    && Character.isUpperCase(current);
            boolean acronymFollowedByWord = index > 0
                    && index + 1 < name.length()
                    && Character.isUpperCase(name.charAt(index - 1))
                    && Character.isUpperCase(current)
                    && Character.isLowerCase(name.charAt(index + 1));
            if (lowerOrDigitFollowedByUpper || acronymFollowedByWord) {
                appendWord(result, word, capitalizeFirst);
            }
            word.append(current);
        }
        appendWord(result, word, capitalizeFirst);
        return result.toString();
    }

    private static void appendWord(StringBuilder result, StringBuilder word, boolean capitalizeFirst) {
        if (word.isEmpty()) {
            return;
        }
        String lower = word.toString().toLowerCase(Locale.ROOT);
        if (result.length() > 0 || capitalizeFirst) {
            result.append(Character.toUpperCase(lower.charAt(0)));
            result.append(lower.substring(1));
        } else {
            result.append(lower);
        }
        word.setLength(0);
    }

    private static boolean isLowerOrDigit(char value) {
        return Character.isLowerCase(value) || Character.isDigit(value);
    }
}
