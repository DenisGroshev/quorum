package app.quorum.session.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    void generate_returnsTenCharacterCode() {
        assertThat(generator.generate()).hasSize(10);
    }

    @Test
    void generate_usesOnlyUnambiguousAlphabet() {
        assertThat(generator.generate()).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz]{10}");
    }

    @Test
    void generate_tenThousandCodes_allUnique() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> codes.add(generator.generate()));

        assertThat(codes).hasSize(10_000);
    }
}
