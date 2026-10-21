///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS io.github.limn-toolkit:limn-demo:0.9.0

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the published demo with a Mesa build loaded first, when {@code -Dmesa.dir} names one.
 *
 * <p>GLFW asks Windows for {@code opengl32.dll} by name, and Windows hands back a module already
 * loaded under that name wherever it came from, so loading Mesa's by full path before the demo
 * starts is the whole installation. The order is dependencies first: {@code dxil.dll}, which the
 * d3d12 driver loads by name to sign its shaders, then any other library of the build, then
 * {@code libgallium_wgl.dll}, which {@code opengl32.dll} imports, and {@code opengl32.dll} last.
 */
public class Probe {

    public static void main(String[] args) throws IOException {
        String dir = System.getProperty("mesa.dir", "");
        if (!dir.isEmpty()) {
            for (Path library : loadOrder(Path.of(dir))) {
                System.load(library.toAbsolutePath().toString());
                System.out.println("PROBE loaded " + library.getFileName());
            }
        }
        limn.demo.Main.main(args);
    }

    private static List<Path> loadOrder(Path dir) throws IOException {
        List<Path> first = new ArrayList<>();
        List<Path> middle = new ArrayList<>();
        List<Path> last = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".dll")).sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase();
                if (name.equals("dxil.dll")) {
                    first.add(file);
                } else if (name.equals("libgallium_wgl.dll") || name.equals("opengl32.dll")) {
                    last.add(file);
                } else {
                    middle.add(file);
                }
            }
        }
        // "libgallium_wgl.dll" sorts before "opengl32.dll", which is the order the import needs.
        List<Path> order = new ArrayList<>(first);
        order.addAll(middle);
        order.addAll(last);
        return order;
    }
}
