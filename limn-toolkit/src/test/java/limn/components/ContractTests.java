package limn.components;

import limn.testing.a11y.ContractCase;
import org.junit.jupiter.api.DynamicTest;

import java.util.List;
import java.util.stream.Stream;

/**
 * Turns a per-shape contract's cases into JUnit dynamic tests: the fixtures know no framework,
 * and a widget's contract test is one {@code @TestFactory} method returning this.
 */
final class ContractTests {

    private ContractTests() {
    }

    static Stream<DynamicTest> of(List<ContractCase> cases) {
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), c.body()::run));
    }
}
