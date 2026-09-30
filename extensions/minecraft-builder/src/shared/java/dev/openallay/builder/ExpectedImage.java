package dev.openallay.builder;

import dev.openallay.builder.storage.BlockSpec;

/** Compare the original capture, not a newly captured baseline, before applying derived edits. */
final class ExpectedImage {
    private ExpectedImage() {}
    static void requireUnchanged(String expected, String current, String position) {
        if (expected != null && !BlockSpec.fromJson(expected).equals(BlockSpec.fromJson(current)))
            throw new BuilderException("concurrent_edit", "Block changed after capture at " + position);
    }
}
