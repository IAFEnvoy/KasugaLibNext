package lib.kasuga.rendering.models.mc.typo.fmt;

import com.google.gson.*;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loader for FMT archive files: MTB (Model.txt) and FMTB (model.jtmt). */
public final class FmtArchiveLoader implements ModelLoader<ZipHelper, ResourceLocation, Integer> {
    private final String name;
    private final MaterialSetBuilder<Integer> materials;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources = new HashMap<>();
    private ZipHelper archive;
    private ResourceLocation identifier;

    public FmtArchiveLoader(String name) { this.name = name; this.materials = new MaterialSetBuilder<>(this); }

    @Override public Map<ResourceLocation, Model> load(ResourceLocation id, ZipHelper input) {
        archive = input; identifier = id;
        List<BoxDef> boxes = id.getPath().endsWith(".mtb") ? parseMtb(input) : parseJtmt(input);
        Material material = buildMaterial();
        List<Vertex> vertices = new ArrayList<>(); List<Mesh> meshes = new ArrayList<>();
        Bone root = new Bone("root", new Transform(), null);
        for (BoxDef box : boxes) appendBox(box, material, root, vertices, meshes);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new lib.kasuga.rendering.models.uml.structure.skeleton.Anchor[0], null, new Transform());
        Model model = new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new), new Bone[]{root}, skeleton,
                materials.endMaterialSet(), MeshMode.QUADS, null, null);
        return Map.of(id, model);
    }

    private Material buildMaterial() {
        ZipResource texture = firstTexture();
        int width = 16, height = 16;
        Object source = MissingTextureAtlasSprite.getLocation();
        ResourceLocation location = MissingTextureAtlasSprite.getLocation();
        if (texture != null) {
            try {
                var image = ImageIO.read(new ByteArrayInputStream(bytes(texture.buffer())));
                if (image != null) {
                    width = image.getWidth(); height = image.getHeight();
                    location = ResourceLocation.fromNamespaceAndPath(identifier.getNamespace(), "fmt/" + Integer.toHexString(identifier.hashCode()));
                    source = Pair.of(location, image); Constants.TEXTURE_BASIC.load(source);
                }
            } catch (Exception ignored) { }
        }
        final ResourceLocation textureLocation = location;
        MCTexture textureData = new MCTexture("main", () -> new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, textureLocation), width, height,
                new MCTextureData(source, Constants.TEXTURE_BASIC));
        materials.beginMaterial().registerTexture(0, textureData).useTexture(0).endMaterial(0);
        return materials.getNamedMaterial(0);
    }

    private ZipResource firstTexture() {
        for (ZipResource resource : archive.searchNameForResource(n -> n.equals("model.png") || n.equals("texture.png") || n.startsWith("texture-"))) return resource;
        return null;
    }

    private static byte[] bytes(java.nio.ByteBuffer buffer) { var copy = buffer.asReadOnlyBuffer(); byte[] data = new byte[copy.remaining()]; copy.get(data); return data; }

    private List<BoxDef> parseMtb(ZipHelper input) {
        ZipResource resource = input.getResource("Model.txt"); if (resource == null) return List.of();
        String text = new String(bytes(resource.buffer()), StandardCharsets.UTF_8);
        // Processors use SerialContext.peek(); populate through a tiny context bridge is not possible,
        // so parse the stable MTB columns directly here.
        List<BoxDef> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\\|", -1);
            if (p.length < 20 || !p[0].equals("Element")) continue;
            if (!p[5].equals("Box") && !p[5].equals("ShapeBox")) continue;
            try { result.add(new BoxDef(new Vector3f(f(p[6]), f(p[7]), f(p[8])), new Vector3f(f(p[9]), f(p[10]), f(p[11])), new Vector3f(f(p[15]), f(p[16]), f(p[17])), new Vector2i(i(p[18]), i(p[19])))); }
            catch (RuntimeException ignored) { }
        }
        return result;
    }

    private List<BoxDef> parseJtmt(ZipHelper input) {
        ZipResource resource = input.getResource("model.jtmt"); if (resource == null) return List.of();
        JsonObject root = JsonParser.parseString(new String(bytes(resource.buffer()), StandardCharsets.UTF_8)).getAsJsonObject();
        List<BoxDef> result = new ArrayList<>(); JsonObject groups = root.has("groups") ? root.getAsJsonObject("groups") : new JsonObject();
        for (JsonElement group : groups.entrySet().stream().map(Map.Entry::getValue).toList()) {
            JsonArray polys = group.getAsJsonObject().getAsJsonArray("polygons"); if (polys == null) continue;
            for (JsonElement element : polys) { JsonObject p = element.getAsJsonObject(); if (!"box".equalsIgnoreCase(p.has("type") ? p.get("type").getAsString() : "")) continue;
                result.add(new BoxDef(new Vector3f(num(p,"pos_x"),num(p,"pos_y"),num(p,"pos_z")), new Vector3f(num(p,"width",1),num(p,"height",1),num(p,"depth",1)), new Vector3f(num(p,"off_x"),num(p,"off_y"),num(p,"off_z")), new Vector2i((int)num(p,"texture_x",0),(int)num(p,"texture_y",0)))); }
        }
        return result;
    }

    private static float f(String s) { return Float.parseFloat(s); } private static int i(String s) { return Integer.parseInt(s); }
    private static float num(JsonObject o, String key) { return num(o,key,0); }
    private static float num(JsonObject o, String key, double def) { return o.has(key) ? o.get(key).getAsFloat() : (float)def; }

    private void appendBox(BoxDef box, Material material, Bone root, List<Vertex> vertices, List<Mesh> meshes) {
        Vector3f min = new Vector3f(box.position).add(box.offset).mul(1f / 16f);
        Vector3f max = new Vector3f(min).add(new Vector3f(box.size).mul(1f / 16f));
        Vector3f[][] faces = {
                {new Vector3f(min.x,min.y,max.z),new Vector3f(max.x,min.y,max.z),new Vector3f(max.x,min.y,min.z),new Vector3f(min.x,min.y,min.z)},
                {new Vector3f(min.x,max.y,min.z),new Vector3f(max.x,max.y,min.z),new Vector3f(max.x,max.y,max.z),new Vector3f(min.x,max.y,max.z)},
                {new Vector3f(max.x,min.y,min.z),new Vector3f(min.x,min.y,min.z),new Vector3f(min.x,max.y,min.z),new Vector3f(max.x,max.y,min.z)},
                {new Vector3f(min.x,min.y,max.z),new Vector3f(max.x,min.y,max.z),new Vector3f(max.x,max.y,max.z),new Vector3f(min.x,max.y,max.z)},
                {new Vector3f(min.x,min.y,min.z),new Vector3f(min.x,min.y,max.z),new Vector3f(min.x,max.y,max.z),new Vector3f(min.x,max.y,min.z)},
                {new Vector3f(max.x,min.y,max.z),new Vector3f(max.x,min.y,min.z),new Vector3f(max.x,max.y,min.z),new Vector3f(max.x,max.y,max.z)}};
        for (Vector3f[] face : faces) { Vector3f normal = new Vector3f(face[1]).sub(face[0]).cross(new Vector3f(face[2]).sub(face[0])).normalize(); Mesh mesh = new Mesh(new Vertex[4], normal, new Transform(), new Material[]{material}, null); for (int n=0;n<4;n++) { Vertex v=new Vertex(face[n],null); v.addUV(mesh,material,new Vector2f((box.uv.x + (n==1||n==2?box.size.x:0))/Math.max(1,material.getTextures()[0].getWidth()), (box.uv.y + (n>=2?box.size.y:0))/Math.max(1,material.getTextures()[0].getHeight()))); v.setBinding(new BoneBinding(new Pair[]{Pair.of(root,1f)}, BoneBindingFunc.BDEF,null)); mesh.getVertices()[n]=v; vertices.add(v);} meshes.add(mesh); }
    }

    private record BoxDef(Vector3f position, Vector3f size, Vector3f offset, Vector2i uv) {}
    @Override public MaterialSetBuilder<Integer> materialSetBuilder(){return materials;} @Override public String getName(){return name;}
    @Override public boolean isValidInput(Object input){return input instanceof ZipHelper;} @Override public HashMap<SourceType,HashMap<String,SourceManager<?>>> getSidedSources(){return sidedSources;}
    @Override public Texture loadTexture(Object id){return materials.getTexture((Integer)id);}
}
