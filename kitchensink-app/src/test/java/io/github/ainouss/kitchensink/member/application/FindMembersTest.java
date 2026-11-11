package io.github.ainouss.kitchensink.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The paging checks of {@link FindMembers}, without a database. */
class FindMembersTest {

    private final MemberRepository members = mock(MemberRepository.class);
    private final FindMembers find = new FindMembers(members);

    @Test
    void aPageWhoseSkipWouldOverflowAnIntIsRefusedBeforeTheQuery() {
        assertThatThrownBy(() -> find.page(Integer.MAX_VALUE / 100 + 1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beyond the last member");
        verify(members, never()).page(anyInt(), anyInt());
    }

    @Test
    void theLastPageTheDatabaseCanSkipToIsServed() {
        when(members.page(Integer.MAX_VALUE / 100, 100)).thenReturn(List.of());
        assertThat(find.page(Integer.MAX_VALUE / 100, 100).content()).isEmpty();
    }

    @Test
    void negativePagesAndSizesOutOfRangeAreRefused() {
        assertThatThrownBy(() -> find.page(-1, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> find.page(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> find.page(0, FindMembers.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
