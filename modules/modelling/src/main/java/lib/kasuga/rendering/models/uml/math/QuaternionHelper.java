package lib.kasuga.rendering.models.uml.math;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public class QuaternionHelper {

    public static final Vector3f ZERO = new Vector3f(0, 0, 0);

    public static Quaternionf fromXYZAngle(float x, float y, float z, boolean degrees) {
        if (degrees) {
            x = (float) Math.toRadians(x);
            y = (float) Math.toRadians(y);
            z = (float) Math.toRadians(z);
        }
        float i, j, k, r;
        float f = sin(0.5F * x);
        float f1 = cos(0.5F * x);
        float f2 = sin(0.5F * y);
        float f3 = cos(0.5F * y);
        float f4 = sin(0.5F * z);
        float f5 = cos(0.5F * z);

        i = f * f3 * f5 + f1 * f2 * f4;
        j = f1 * f2 * f5 - f * f3 * f4;
        k = f * f2 * f5 + f1 * f3 * f4;
        r = f1 * f3 * f5 - f * f2 * f4;

        return new Quaternionf(i, j, k, r);
    }

    public static Quaternionf fromXYZRadians(float x, float y, float z) {
        return fromXYZAngle(x, y, z, false);
    }

    public static Quaternionf fromXYZDegrees(float x, float y, float z) {
        return fromXYZAngle(x, y, z, true);
    }

    public static Quaternionf fromXYZDegrees(Vector3f angles) {
        return fromXYZDegrees(angles.x(), angles.y(), angles.z());
    }

    public static float sin(float angle) {
        return (float) Math.sin(angle);
    }

    public static float cos(float angle) {
        return (float) Math.cos(angle);
    }

    public static float tan(float angle) {
        return (float) Math.tan(angle);
    }
}
