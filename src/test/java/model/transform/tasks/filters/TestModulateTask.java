package model.transform.tasks.filters;

import model.transform.base.TransformTask;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TestModulateTask {

    @Test
    public void testToString() {
        String correct = "modulate="
                + "brightness:155,"
                + "hue:155,"
                + "saturation:155";

        TransformTask task = new ModulateTask.Builder()
                .brightness(155)
                .hue(155)
                .saturation(155)
                .build();

        String output = task.toString();

        String message = String.format("Task string malformed\nCorrect: %s\nOutput: %s", correct, output);
        assertTrue(message, output.equals(correct));
    }
}
