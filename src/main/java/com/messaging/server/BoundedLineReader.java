package com.messaging.server;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Reads newline-terminated lines but never buffers more than maxChars
 * characters, so a client cannot exhaust memory by sending a huge line.
 */
public final class BoundedLineReader {

    public static class LineTooLongException extends IOException {
        public LineTooLongException(int maxChars) {
            super("Line exceeds " + maxChars + " characters");
        }
    }

    private BoundedLineReader() {
    }

    /** Returns the next line without its terminator, or null at end of stream. */
    public static String readLine(BufferedReader reader, int maxChars)
            throws IOException {

        StringBuilder line = new StringBuilder();
        int c;

        while ((c = reader.read()) != -1) {

            if (c == '\n') {
                return stripCarriageReturn(line);
            }

            if (line.length() >= maxChars) {
                throw new LineTooLongException(maxChars);
            }

            line.append((char) c);
        }

        // End of stream: return a trailing unterminated line, if any
        return line.length() == 0 ? null : stripCarriageReturn(line);
    }

    private static String stripCarriageReturn(StringBuilder line) {
        int len = line.length();
        if (len > 0 && line.charAt(len - 1) == '\r') {
            line.setLength(len - 1);
        }
        return line.toString();
    }
}
