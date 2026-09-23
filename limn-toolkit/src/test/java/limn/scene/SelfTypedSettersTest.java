package limn.scene;

import limn.components.chart.CartesianChart;
import limn.components.chart.Chart;
import limn.scene.layout.Container;
import limn.scene.layout.Flex;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every public setter a self-typed base class declares returns {@code W}, so a chain started on
 * a concrete widget stays that widget. {@code CartesianChart.setStacked} once returned
 * {@code CartesianChart<?>}, and {@code chart.setStacked(true).setBarRadius(4)} did not compile.
 */
class SelfTypedSettersTest {

    @Test
    void everySetterOnASelfTypedBaseReturnsItsOwnType() {
        List<String> wrong = new ArrayList<>();
        for (Class<?> base : List.of(Widget.class, Container.class, Flex.class, Chart.class,
                CartesianChart.class)) {
            TypeVariable<?> self = base.getTypeParameters()[0];
            for (Method method : base.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
                        && method.getName().startsWith("set")
                        && !self.equals(method.getGenericReturnType())) {
                    wrong.add(base.getSimpleName() + "." + method.getName()
                            + " returns " + method.getGenericReturnType().getTypeName());
                }
            }
        }
        assertEquals(List.of(), wrong);
    }
}
