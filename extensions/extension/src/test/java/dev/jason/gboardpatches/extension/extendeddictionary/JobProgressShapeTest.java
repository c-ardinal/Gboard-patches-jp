package dev.jason.gboardpatches.extension.extendeddictionary;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The patched Gboard fails with IncompatibleClassChangeError when it calls a lambda body or
 * static method that D8 placed on this interface, so the interface keeps only its own methods.
 */
public final class JobProgressShapeTest {
    @Test
    public void jobProgressHasNoStaticOrSyntheticMethods() {
        List<String> offending = new ArrayList<String>();
        for (Method method : ExtendedDictionaryImporter.JobProgress.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                offending.add(method.getName());
            }
        }
        Assert.assertTrue("Static or synthetic methods on JobProgress: " + offending,
                offending.isEmpty());
    }

    @Test
    public void phaseProgressForwardsToItsPhase() {
        final List<String> seen = new ArrayList<String>();
        ExtendedDictionaryImporter.JobProgress job = new ExtendedDictionaryImporter.JobProgress() {
            @Override
            public void expect(PhasedEta.Phase phase, long units) {
            }

            @Override
            public void onProgress(PhasedEta.Phase phase, int done, int total) {
                seen.add(phase + ":" + done + "/" + total);
            }
        };
        job.phase(PhasedEta.Phase.PARSE).onProgress(3, 3);
        ExtendedDictionaryImporter.onlyPhase(PhasedEta.Phase.INSERT,
                (done, total) -> seen.add("only:" + done + "/" + total))
                .onProgress(PhasedEta.Phase.INSERT, 1, 2);
        Assert.assertEquals(java.util.Arrays.asList("PARSE:3/3", "only:1/2"), seen);
    }
}
