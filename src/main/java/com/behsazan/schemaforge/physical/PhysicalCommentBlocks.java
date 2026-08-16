package com.behsazan.schemaforge.physical;

import java.util.ArrayList;
import java.util.List;

/** Shared formatting for executable-ready physical option comment blocks. */
public final class PhysicalCommentBlocks {
    private static final String NL = System.lineSeparator();

    private PhysicalCommentBlocks() {
    }

    public static String block(String title, List<String> lines) {
        List<String> content = new ArrayList<>();
        content.add("-- " + title);
        content.addAll(lines);
        return NL + "/*" + NL
                + String.join(NL, content) + NL
                + "*/";
    }
}
