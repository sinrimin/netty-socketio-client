package com.moesrc.socketio;

import io.netty.buffer.ByteBuf;

public class UTF8CharsScanner {

    /**
     * Lookup table used for determining which input characters need special
     * handling when contained in text segment.
     */
    static final int[] sInputCodes;
    static {
        /*
         * 96 would do for most cases (backslash is ascii 94) but if we want to
         * do lookups by raw bytes it's better to have full table
         */
        int[] table = new int[256];
        // Control chars and non-space white space are not allowed unquoted
        for (int i = 0; i < 32; ++i) {
            table[i] = -1;
        }
        // And then string end and quote markers are special too
        table['"'] = 1;
        table['\\'] = 1;
        sInputCodes = table;
    }

    /**
     * Additionally we can combine UTF-8 decoding info into similar data table.
     */
    static final int[] sInputCodesUtf8;
    static {
        int[] table = new int[sInputCodes.length];
        System.arraycopy(sInputCodes, 0, table, 0, sInputCodes.length);
        for (int c = 128; c < 256; ++c) {
            int code;

            // We'll add number of bytes needed for decoding
            if ((c & 0xE0) == 0xC0) { // 2 bytes (0x0080 - 0x07FF)
                code = 2;
            } else if ((c & 0xF0) == 0xE0) { // 3 bytes (0x0800 - 0xFFFF)
                code = 3;
            } else if ((c & 0xF8) == 0xF0) {
                // 4 bytes; double-char with surrogates and all...
                code = 4;
            } else {
                // And -1 seems like a good "universal" error marker...
                code = -1;
            }
            table[c] = code;
        }
        sInputCodesUtf8 = table;
    }

    private int getCharTailIndex(ByteBuf inputBuffer, int i) {
        int c = (int) inputBuffer.getByte(i) & 0xFF;
        switch (sInputCodesUtf8[c]) {
        case 2: // 2-byte UTF
            i += 2;
            break;
        case 3: // 3-byte UTF
            i += 3;
            break;
        case 4: // 4-byte UTF
            i += 4;
            break;
        default:
            i++;
            break;
        }
        return i;
    }

    public int getLength(ByteBuf inputBuffer, int start) {
        int len = 0;
        for (int i = start; i < inputBuffer.writerIndex();) {
            i = getCharTailIndex(inputBuffer, i);
            len++;
        }
        return len;
    }

    public int getActualLength(ByteBuf inputBuffer, int length) {
        int len = 0;
        int start = inputBuffer.readerIndex();
        for (int i = inputBuffer.readerIndex(); i < inputBuffer.readableBytes() + inputBuffer.readerIndex();) {
            i = getCharTailIndex(inputBuffer, i);
            len++;
            if (length == len) {
                return i-start;
            }
        }
        throw new IllegalStateException();
    }


    public int findTailIndex(ByteBuf inputBuffer, int start, int end,
            int charsToRead) {
        int len = 0;
        int i = start;
        while (i < end) {
            i = getCharTailIndex(inputBuffer, i);
            len++;
            if (charsToRead == len) {
                break;
            }
        }
        return i;
    }

}
