package limn.backend.lwjgl.floors;

import limn.backend.lwjgl.floors.NativeHeaders.Found;
import limn.backend.lwjgl.floors.OsFloors.KnownException;
import limn.backend.lwjgl.floors.OsFloors.Target;
import limn.backend.lwjgl.floors.OsFloors.Version;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The verdict over what {@link NativeHeaders} read: a pure function of the binaries, the floors
 * and the exceptions, so that a test can hand it a lowered floor or a stale exception and watch it
 * fail, which is how the check is proved to be able to fail at all.
 */
final class FloorCheck {

    private FloorCheck() {
    }

    /** @return the floors {@link OsFloors} declares, as a map a test may change a copy of */
    static Map<Target, Version> declaredFloors() {
        Map<Target, Version> floors = new EnumMap<>(Target.class);
        for (Target target : Target.values()) {
            floors.put(target, target.floor());
        }
        return floors;
    }

    /**
     * @param found      every binary read
     * @param floors     the newest version each platform's binaries may ask for
     * @param exceptions the known exceptions
     * @return one line per failure: a binary over its floor and not excused, or an exception that
     *         excuses nothing any more; empty when everything holds
     */
    static List<String> failures(List<Found> found, Map<Target, Version> floors, List<KnownException> exceptions) {
        List<String> failures = new ArrayList<>();
        Set<KnownException> covering = new HashSet<>();
        Set<KnownException> used = new HashSet<>();
        for (Found f : found) {
            Target target = f.binary().target();
            for (KnownException e : exceptions) {
                if (e.covers(f.jar())) {
                    covering.add(e);
                }
            }
            Version requires = f.binary().requires();
            Version floor = floors.get(target);
            if (requires == null || !requires.isAfter(floor)) {
                continue;
            }
            KnownException excuse = exceptions.stream()
                    .filter(e -> e.covers(f.jar()) && e.targets().contains(target))
                    .findFirst().orElse(null);
            if (excuse != null && !requires.isAfter(excuse.ceiling())) {
                used.add(excuse);
                continue;
            }
            failures.add(f.binary().name() + " asks for " + target.measure() + " " + requires
                    + ", newer than " + target + "'s floor of " + floor
                    + (excuse == null ? "" : " and than the " + excuse.ceiling() + " its exception allows"));
        }
        for (KnownException e : exceptions) {
            if (!covering.contains(e)) {
                failures.add("the exception for " + e.artifact() + " " + e.version() + " is stale: no jar of "
                        + "that exact version is resolved any more, so the pin has moved; remove it from "
                        + "OsFloors.KNOWN_EXCEPTIONS (it said: " + e.why() + ")");
            } else if (!used.contains(e)) {
                failures.add("the exception for " + e.artifact() + " " + e.version() + " is stale: its "
                        + "libraries are within the floors now; remove it from OsFloors.KNOWN_EXCEPTIONS");
            }
        }
        return failures;
    }

    /**
     * @param found    every binary read
     * @param payloads the jar-name prefix of each payload that must ship every platform
     * @return one line per payload that has no binary for some platform: a check that read nothing
     *         would otherwise pass
     */
    static List<String> gaps(List<Found> found, List<String> payloads) {
        List<String> gaps = new ArrayList<>();
        for (String payload : payloads) {
            Set<Target> seen = EnumSet.noneOf(Target.class);
            for (Found f : found) {
                if (f.jar().startsWith(payload + "-")) {
                    seen.add(f.binary().target());
                }
            }
            Set<Target> missing = EnumSet.allOf(Target.class);
            missing.removeAll(seen);
            if (!missing.isEmpty()) {
                gaps.add(payload + " has no native read for " + missing);
            }
        }
        return gaps;
    }
}
