package lib.kasuga.rendering.models.uml.math;

import org.joml.*;

import java.lang.Math;
import java.util.Objects;
import java.util.Vector;

public class Transform {


    private final Matrix4f transform;
    private final Matrix3f normal;

    public Transform(Matrix4f transform, Matrix3f normal) {
        this.transform = transform;
        this.normal = normal;
    }

    public Transform() {
        this.transform = new Matrix4f().identity();
        this.normal = new Matrix3f().identity();
    }

    public Vector3f apply(Vector3f vector) {
        return transform.transformPosition(vector);
    }

    public Vector3f applyDirection(Vector3f vector) {
        return transform.transformDirection(vector);
    }

    public Vector3f applyInvert(Vector3f vector) {
        return invertTransform().transformPosition(vector);
    }

    public Transform copy() {
        return new Transform(new Matrix4f(transform), new Matrix3f(normal));
    }

    public Matrix4f transform() {
        return transform;
    }

    public Matrix4f invertTransform() {
        return new Matrix4f(transform).invert();
    }

    public Transform invert() {
        return new Transform(invertTransform(), new Matrix3f(normal).invert());
    }

    public Matrix3f normal() {
        return normal;
    }

    public Transform mul(Transform other) {
        transform.mul(other.transform);
        normal.mul(other.normal);
        return this;
    }

    public Transform set(Transform other) {
        transform.set(other.transform);
        normal.set(other.normal);
        return this;
    }

    public Transform translate(float x, float y, float z) {
        transform.translate(x, y, z);
        return this;
    }

    public Transform translate(Vector3f vec) {
        transform.translate(vec);
        return this;
    }

    public Transform mul(Quaternionf quaternion) {
        transform.rotate(quaternion);
        normal.rotate(quaternion);
        return this;
    }

    public Transform rotate(float x, float y, float z, boolean degrees) {
        Quaternionf quaternion = QuaternionHelper.fromXYZAngle(x, y, z, degrees);
        return mul(quaternion);
    }

    public Transform rotate(Vector3f rotation, boolean degrees) {
        Quaternionf quaternionf = QuaternionHelper.fromXYZAngle(rotation.x(), rotation.y(), rotation.z(), degrees);
        return mul(quaternionf);
    }

    public Transform scale(float x, float y, float z) {
        transform.mul(createScalingMatrix(x, y, z));

        if (x == y && y == z) {
            if (x > 0) {
                return this;
            }

            normal.scale(-1.0F);
        }

        float f = 1.0F / x;
        float f1 = 1.0F / y;
        float f2 = 1.0F / z;
        float f3 = fastInvCubeRoot(f * f1 * f2);
        normal.mul(createScalingMatrix3f(f3 * f, f3 * f1, f3 * f2));
        return this;
    }

    public Transform setIdentity() {
        transform.identity();
        normal.identity();
        return this;
    }

    public boolean isIdentity() {
        return close(transform.m00(), 1.0f) &&
                close(transform.m01(), 0.0f) &&
                close(transform.m02(), 0.0f) &&
                close(transform.m03(), 0.0f) &&
                close(transform.m10(), 0.0f) &&
                close(transform.m11(), 1.0f) &&
                close(transform.m12(), 0.0f) &&
                close(transform.m13(), 0.0f) &&
                close(transform.m20(), 0.0f) &&
                close(transform.m21(), 0.0f) &&
                close(transform.m22(), 1.0f) &&
                close(transform.m23(), 0.0f) &&
                close(transform.m30(), 0.0f) &&
                close(transform.m31(), 0.0f) &&
                close(transform.m32(), 0.0f) &&
                close(transform.m33(), 1.0f);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 1.0e-6f;
    }

    public static Matrix4f createScalingMatrix(float x, float y, float z) {
        Matrix4f scalingMatrix = new Matrix4f();
        scalingMatrix.m00(x);
        scalingMatrix.m11(y);
        scalingMatrix.m22(z);
        scalingMatrix.m33(1F);
        return scalingMatrix;
    }

    public static Matrix3f createScalingMatrix3f(float x, float y, float z) {
        Matrix3f scalingMatrix = new Matrix3f();
        scalingMatrix.m00(x);
        scalingMatrix.m11(y);
        scalingMatrix.m22(z);
        return scalingMatrix;
    }

    public static float fastInvCubeRoot(float pNumber) {
        int i = Float.floatToIntBits(pNumber);
        i = 1419967116 - i / 3;
        float f = Float.intBitsToFloat(i);
        f = 0.6666667F * f + 1.0F / (3.0F * f * f * pNumber);
        return 0.6666667F * f + 1.0F / (3.0F * f * f * pNumber);
    }

    public Vector3f getPosition() {
        return transform.getTranslation(new Vector3f());
    }

    public Quaternionf getRotation() {
        return transform.getNormalizedRotation(new Quaternionf());
    }

    public DualQuaternion toDualQuaternion() {
        return new DualQuaternion(this.transform);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Transform trans)) return false;
        return Objects.equals(trans.transform, this.transform) &&
                Objects.equals(trans.normal, this.normal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transform, normal);
    }
}
