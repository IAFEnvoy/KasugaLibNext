package lib.kasuga.rendering.models.uml.math.binding;

import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.DualQuaternion;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.structure.Pair;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface BoneBindingFunc {

    Vertex apply(Vertex vertex, List<BoneContext> context);

    BoneBindingFunc IDENTITY = (vertex, context) -> vertex;

    BoneBindingFunc BDEF = (vertex, context) -> {
        if (context.isEmpty()) {
            return IDENTITY.apply(vertex, context);
        }
        Vector3f posFinal = new Vector3f();
        HashMap<Mesh, Vector3f> normalFinals = new HashMap<>();
        for (BoneContext<BoneData> boneContext : context) {
            Vector3f posLocal = new Vector3f(vertex.getPosition());
            posLocal = boneContext.invTransform().apply(posLocal);
            Vector3f posTransformed = boneContext.absTransform().apply(posLocal);
            posTransformed.mul(boneContext.weight());
            posFinal.add(posTransformed);
            for (Map.Entry<Mesh, Vector3f> meshAndNormal : vertex.getNormals().entrySet()) {
                Vector3f normalTransformed = boneContext.absTransform().normal().transform(new Vector3f(meshAndNormal.getValue()));
                normalTransformed.mul(boneContext.weight());
                if (normalFinals.containsKey(meshAndNormal.getKey())) {
                    normalFinals.get(meshAndNormal.getKey()).add(normalTransformed);
                } else {
                    normalFinals.put(meshAndNormal.getKey(), normalTransformed);
                }
            }
        }
        normalFinals.forEach((k, v) -> v.normalize());
        return new Vertex(vertex, posFinal, normalFinals);
    };

    BoneBindingFunc SDEF = (vertex, context) -> {
        if (context.size() < 2) {
            return BDEF.apply(vertex, context);
        }

        // 获取该顶点在绑定时，在世界空间中的位置
        Vector3f posOrg = vertex.getPosition();

        // 两个骨骼的数据
        BoneContext boneContext0 = context.get(0);
        BoneContext boneContext1 = context.get(1);

        // 计算出该顶点在两个骨骼空间中的位置
        Vector3f p0 = new Vector3f(vertex.getPosition());
        Vector3f p1 = new Vector3f(vertex.getPosition());
        p0 = boneContext0.invTransform().apply(p0);
        p1 = boneContext1.invTransform().apply(p1);

        // 获取该顶点所对应的两个辅助参数r0和r1在骨骼空间中的位置
        Vector3f r0, r1;
        if ((vertex.getData() instanceof SDEFBoneBindingData sdefData)) {
            r0 = sdefData.getSDEFData().r0();
            r1 = sdefData.getSDEFData().r1();
        } else {
            r0 = new Vector3f(p0);
            r1 = new Vector3f(p1);
        }

        // 获取该顶点所对应的两个骨骼的关节j0和j1在世界空间中的位置
        Vector3f j0 = new Vector3f(p0).sub(r0);
        Vector3f j1 = new Vector3f(p1).sub(r1);

        Vector3f j0Bind = boneContext0.bindTransform().apply(j0);
        Vector3f j1Bind = boneContext1.bindTransform().apply(j1);
        Vector3f j0Current = boneContext0.absTransform().apply(j0);
        Vector3f j1Current = boneContext1.absTransform().apply(j1);

        // 将r0和r1从骨骼空间转换到世界空间
        r0 = boneContext0.absTransform().apply(r0);
        r1 = boneContext1.absTransform().apply(r1);

        // 获取该顶点所对应的两个骨骼的旋转
        Quaternionf q0 = boneContext0.absTransform().getRotation();
        Quaternionf q1 = boneContext1.absTransform().getRotation();

        // 绑定姿态下，世界坐标中的相对矢量
        Vector3f a0 = new Vector3f(posOrg).sub(j0Bind);
        Vector3f a1 = new Vector3f(posOrg).sub(j1Bind);

        // 当前帧当中，受单根骨骼影响的顶点的相对位置
        Vector3f v0 = new Vector3f(j0Current).add(q0.transform(a0));
        Vector3f v1 = new Vector3f(j1Current).add(q1.transform(a1));

        // 权重归一化
        float w0 = boneContext0.weight();
        float w1 = boneContext1.weight();
        float sum = w0 + w1;
        w0 /= sum;
        w1 /= sum;

        // 获取插值辅助点和关节中心
        Vector3f r = new Vector3f(r0);
        r = r.mul(w0).add(new Vector3f(r1).mul(w1));
        Vector3f j = new Vector3f(j0Current);
        j = j.mul(w0).add(new Vector3f(j1Current).mul(w1));

        // 计算最终位置
        Vector3f posFinal = new Vector3f(v0).mul(w0).add(new Vector3f(v1).mul(w1));
        posFinal = posFinal.add(r).sub(j);

        // 计算法线
        HashMap<Mesh, Vector3f> normalFinals = new HashMap<>();
        for (Map.Entry<Mesh, Vector3f> meshAndNormal : vertex.getNormals().entrySet()) {
            Vector3f normalOrg = meshAndNormal.getValue();
            Vector3f n0 = boneContext0.absTransform().normal().transform(new Vector3f(normalOrg));
            Vector3f n1 = boneContext1.absTransform().normal().transform(new Vector3f(normalOrg));
            Vector3f normalFinal = new Vector3f(n0).mul(w0).add(new Vector3f(n1).mul(w1));
            normalFinal.normalize();
            normalFinals.put(meshAndNormal.getKey(), normalFinal);
        }

        return new Vertex(vertex, posFinal, normalFinals);
    };

    BoneBindingFunc QDEF = (vertex, context) -> {
        if (context.isEmpty()) {
            return IDENTITY.apply(vertex, context);
        }
        Vector3f posOrg = vertex.getPosition();
        List<Pair<DualQuaternion, Float>> dualQuaternions = new ArrayList<>();
        for (BoneContext<BoneData> boneContext : context) {
            DualQuaternion dq = boneContext.absTransform().toDualQuaternion();
            dualQuaternions.add(Pair.of(dq, boneContext.weight()));
        }
        DualQuaternion result = DualQuaternion.blend(dualQuaternions);
        Vector3f posFinal = result.transformPoint(new Vector3f(posOrg));
        HashMap<Mesh, Vector3f> normalFinals = new HashMap<>();
        for (Map.Entry<Mesh, Vector3f> meshAndNormal : vertex.getNormals().entrySet()) {
            Vector3f normal = new Vector3f(meshAndNormal.getValue());
            normal = result.getReal().transform(normal);
            normal.normalize();
            normalFinals.put(meshAndNormal.getKey(), normal);
        }
        return new Vertex(vertex, posFinal, normalFinals);
    };
}
