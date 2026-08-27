package lib.kasuga.rendering.models.uml.bridge;

import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;

import java.util.HashMap;

public interface Bridge<R> {
    HashMap<Vertex, Vertex> transformVertices(Model model,
                                              SkeletonInstance skeleton,
                                              Vertex[] vertices);

    Mesh[] transformMeshes(Model model,
                                          SkeletonInstance skeleton,
                                          Mesh[] meshes);

    R getBackendRenderable(ModelInstance modelInstance,
                           HashMap<Vertex, Vertex> vertices, Mesh[] meshes);

    BoneBindingFunc getBoneBindingFunc(Model model,
                                          SkeletonInstance skeleton,
                                          Vertex vertex);

    BackendContext<?, R, ?, ?> getBackendContext(ModelInstance modelInstance);

    default R apply(ModelInstance modelInstance) {
        Model model = modelInstance.getModel();
        SkeletonInstance skeleton = modelInstance.getSkeletonInstance();
        Vertex[] vertices = model.getVertices();
        Mesh[] meshes = model.getMeshes();
        long transformVerticesStart = ModelProfiler.start();
        HashMap<Vertex, Vertex> vertexMap = transformVertices(model, skeleton, vertices);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("bridge.transformVertices", transformVerticesStart,
                    "vertices=" + vertices.length);
        }
        long transformMeshesStart = ModelProfiler.start();
        Mesh[] transformedMeshes = transformMeshes(model, skeleton, meshes);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("bridge.transformMeshes", transformMeshesStart,
                    "meshes=" + meshes.length);
        }
        long backendRenderableStart = ModelProfiler.start();
        R renderable = getBackendRenderable(modelInstance, vertexMap, transformedMeshes);
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("bridge.getBackendRenderable", backendRenderableStart,
                    "meshes=" + transformedMeshes.length);
        }
        return renderable;
    }
}
