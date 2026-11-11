package io.github.ainouss.kitchensink.sync.member;

/** A legacy row that cannot be stored as a member document; it goes to quarantine with this reason. */
public class UnmappableRowException extends Exception {

    public UnmappableRowException(String reason) {
        super(reason);
    }
}
