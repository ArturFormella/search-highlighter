package org.wikimedia.search.highlighter.experimental.source;

import org.wikimedia.search.highlighter.experimental.SourceExtracter;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Extracts strings from a String source.
 */
@SuppressFBWarnings(value = "STT_STRING_PARSING_A_FIELD", justification = "This class IS a parser.")
public final class StringSourceExtracter implements SourceExtracter<String> {
    private final String source;

    public StringSourceExtracter(String source) {
        this.source = source;
    }

    @Override
    public String extract(int startOffset, int endOffset) {
        // This has extra defense just in case thing get weird. Shouldn't happen
        // though.
        startOffset = Math.max(0, startOffset);
        endOffset = Math.min(endOffset, source.length());
        if (startOffset >= endOffset) {
            return "";
        }
        if (Character.isLowSurrogate(source.charAt(startOffset))) {
            startOffset++;
        }
        if (source.length() > endOffset && Character.isLowSurrogate(source.charAt(endOffset))) {
            endOffset--;
        }
        if (startOffset >= endOffset) {
            return "";
        }
        return source.substring(startOffset, endOffset);
    }
}
