package app.quorum.matching.config;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.matching.config.MatchingProperties.Penalties;
import app.quorum.matching.config.MatchingProperties.Weights;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatchingPropertiesValidationTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static MatchingProperties valid() {
        return new MatchingProperties(
                "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);
    }

    @Test
    void wellFormedProperties_hasNoViolations() {
        assertThat(VALIDATOR.validate(valid())).isEmpty();
    }

    @Test
    void negativeWeight_isRejected() {
        MatchingProperties props = new MatchingProperties(
                "w1", new Weights(-1, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

        Set<ConstraintViolation<MatchingProperties>> violations = VALIDATOR.validate(props);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void negativePenalty_isRejected() {
        MatchingProperties props = new MatchingProperties(
                "w1", new Weights(45, 20, 15, 10, 10), new Penalties(-1, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

        assertThat(VALIDATOR.validate(props)).isNotEmpty();
    }

    @Test
    void defaultRatingWhenFewVotes_aboveOne_isRejected() {
        MatchingProperties props = new MatchingProperties(
                "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 1.5, 0.4, 3, 0.5);

        assertThat(VALIDATOR.validate(props)).isNotEmpty();
    }

    @Test
    void maxGenreShare_belowZero_isRejected() {
        MatchingProperties props = new MatchingProperties(
                "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, -0.1, 3, 0.5);

        assertThat(VALIDATOR.validate(props)).isNotEmpty();
    }

    @Test
    void availabilityStub_aboveOne_isRejected() {
        MatchingProperties props = new MatchingProperties(
                "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 1.1);

        assertThat(VALIDATOR.validate(props)).isNotEmpty();
    }

    @Test
    void blankVersion_isRejected() {
        MatchingProperties props = new MatchingProperties(
                " ", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

        assertThat(VALIDATOR.validate(props)).isNotEmpty();
    }
}
