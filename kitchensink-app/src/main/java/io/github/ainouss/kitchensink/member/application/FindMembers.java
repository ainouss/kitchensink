package io.github.ainouss.kitchensink.member.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import io.github.ainouss.kitchensink.member.domain.Member;

/** Reads members: by id, all of them by name, or one page by name. */
@Service
public class FindMembers {

    /** The largest page size the API and the web page allow. */
    public static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository members;

    public FindMembers(MemberRepository members) {
        this.members = members;
    }

    public Optional<Member> byId(long id) {
        return members.findById(id);
    }

    /** Every member, by name then id (R8), one at a time so memory stays low (R17). */
    public void eachByName(Consumer<Member> action) {
        members.forEachByName(action);
    }

    /** The number of members: a fast estimate from MongoDB, exact once writes settle. */
    public long count() {
        return members.count();
    }

    /**
     * @param page 0-based; a page past the last one is empty
     * @param size 1 to {@link #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException when the page or size is out of range, or the page is too far for the
     *                                  database to skip to (its skip is an int)
     */
    public MemberPage page(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page " + page + " of size " + size + " is beyond the last member");
        }
        return new MemberPage(members.page(page, size), page, size, members.count());
    }

    public record MemberPage(List<Member> content, int page, int size, long totalElements) {
    }
}
