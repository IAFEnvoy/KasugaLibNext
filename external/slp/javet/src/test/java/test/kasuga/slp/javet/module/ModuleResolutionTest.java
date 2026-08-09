package test.kasuga.slp.javet.module;

import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.discovery.PackageInfo;
import lib.kasuga.scripting.module.*;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.slp.javet.JavetScriptEngine;
import lib.kasuga.slp.javet.KasugaLibJavet;
import lib.kasuga.slp.javet.module.JavetModuleHandle;
import lib.kasuga.slp.javet.module.JsModuleResolver;
import lib.kasuga.slp.javet.module.PackageAwareRequireResolver;
import lib.kasuga.slp.javet.module.RequireResolver;
import lib.kasuga.slp.javet.value.JavetValuePrimitive;
import org.junit.jupiter.api.*;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ModuleResolutionTest {

    private JavetScriptEngine engine;
    private JsModuleResolver resolver;
    private ScriptEngineType<JavetScriptEngine> engineType;
    private PackageRegistry packageRegistry;
    private BuiltinModuleRegistry builtinRegistry;
    private ScriptEngineType<JavetScriptEngine> savedEngineType;

    @BeforeEach
    void setUp() throws ScriptException {
        savedEngineType = KasugaLibJavet.ENGINE_TYPE;

        resolver = new JsModuleResolver();
        packageRegistry = new PackageRegistry();
        builtinRegistry = new BuiltinModuleRegistry();

        engineType = ScriptEngineType.<JavetScriptEngine>builder(JavetScriptEngine::new)
            .scriptType("javascript")
            .resolver(resolver)
            .build();
        KasugaLibJavet.ENGINE_TYPE = engineType;

        engine = new JavetScriptEngine();
        engine.setRequireResolver(createRequireResolver());
        engine.init(ScriptConsole.errorsToStderr());
    }

    @AfterEach
    void tearDown() {
        KasugaLibJavet.ENGINE_TYPE = savedEngineType;
        if (engine != null) {
            engine.getRuntime().lowMemoryNotification();
            engine.close();
        }
    }

    private RequireResolver createRequireResolver() {
        // Delegate the package-by-name + in-package relative branches to the PRODUCTION resolver class so
        // these 13 tests exercise the same code path real scripts use (only the test-specific builtin branch
        // stays inline here).
        PackageAwareRequireResolver packageAware = new PackageAwareRequireResolver(engine, packageRegistry, resolver);
        return (moduleName, fromSourcePath) -> {
            if (builtinRegistry.has(moduleName)) {
                Object builtin = builtinRegistry.resolve(moduleName);
                var exports = new HashMap<String, ScriptValue>();
                if (builtin instanceof Map<?, ?> map) {
                    for (var entry : map.entrySet()) {
                        exports.put(entry.getKey().toString(), engine.createValue(entry.getValue()));
                    }
                } else {
                    exports.put("default", engine.createValue(builtin));
                }
                return new JavetModuleHandle(exports, "builtin:" + moduleName, engineType);
            }
            return packageAware.resolve(moduleName, fromSourcePath);
        };
    }

    private ResolvedPackage loadTestPackage() throws URISyntaxException {
        Path packPath = Path.of(getClass().getResource("/packs/js_pack").toURI());
        PackageInfo info = new PackageInfo(
            "@test/js-pack", "javascript", null, "1.0.0", "index",
            List.of(),
            new PackageInfo.EntryConfig(List.of(), List.of(), List.of())
        );
        ResolvedPackage pkg = new ResolvedPackage(
            info,
            new test.kasuga.slp.javet.TestPackResources(packPath, "js_pack"),
            "scripts",
            engineType
        );
        packageRegistry.register(pkg);
        return pkg;
    }

    private double asDouble(ScriptValue v) throws ScriptException {
        assertTrue(v instanceof JavetValuePrimitive, "Expected JavetValuePrimitive, got " + v.getClass().getSimpleName());
        return ((JavetValuePrimitive) v).asDouble();
    }

    // --- locateScript tests ---

    @Test
    public void shouldLocateJsFile() throws URISyntaxException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of("utils"));
        assertNotNull(script);
        assertEquals("utils.js", script.filePath());
        assertEquals("scripts", script.owner().packRelativeRoot());
    }

    @Test
    public void shouldFallbackToIndexJs() throws URISyntaxException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of());
        assertNotNull(script);
        assertEquals("index.js", script.filePath());
    }

    @Test
    public void shouldReturnNullForMissingFile() throws URISyntaxException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of("nonexistent"));
        assertNull(script);
    }

    // --- loadModule tests ---

    @Test
    public void shouldLoadUtilsModuleWithCorrectValues() throws URISyntaxException, ScriptException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of("utils"));

        ScriptModuleHandle handle = engine.loadModule(script);
        assertNotNull(handle);
        assertEquals("utils.js", handle.getSourcePath());
        assertEquals(2, handle.getExportNames().size());
        assertTrue(handle.getExportNames().contains("PI"));
        assertTrue(handle.getExportNames().contains("add"));

        // PI = 3.14159
        assertEquals(3.14159, asDouble(handle.getExport("PI")), 1e-5);
    }

    @Test
    public void shouldCacheLoadedModule() throws URISyntaxException, ScriptException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of("utils"));

        ScriptModuleHandle first = engine.loadModule(script);
        ScriptModuleHandle second = engine.getLoadedModule("utils.js");
        assertSame(first, second);
        assertEquals("utils.js", second.getSourcePath());
    }

    // --- require tests ---

    @Test
    public void shouldRequireRelativeModuleAndVerifyValues() throws URISyntaxException, ScriptException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of("index"));

        ScriptModuleHandle handle = engine.loadModule(script);
        assertNotNull(handle);

        // index.js: { value: 42, pi: utils.PI, sum: utils.add(10, 32) }
        assertEquals("42", handle.getExport("value").asString());

        // pi = utils.PI = 3.14159
        double pi = asDouble(handle.getExport("pi"));
        assertEquals(3.14159, pi, 1e-5);

        // sum = utils.add(10, 32) = 42
        double sum = asDouble(handle.getExport("sum"));
        assertEquals(42.0, sum, 1e-10);
    }

    @Test
    public void shouldRequirePackageByNameAndVerifyValues() throws URISyntaxException, ScriptException {
        loadTestPackage();

        ResolvedPackage found = packageRegistry.lookup("@test/js-pack");
        assertEquals("@test/js-pack", found.info().name());
        assertEquals("1.0.0", found.info().version());
        assertEquals("index", found.info().main());

        ResolvedScript script = resolver.locateScript(found, List.of());
        ScriptModuleHandle handle = engine.loadModule(script);

        // Same values as shouldRequireRelativeModuleAndVerifyValues
        assertEquals("42", handle.getExport("value").asString());
        assertEquals(3.14159, asDouble(handle.getExport("pi")), 1e-5);
        assertEquals(42.0, asDouble(handle.getExport("sum")), 1e-10);
    }

    @Test
    public void shouldHandleCircularRequire() throws URISyntaxException, ScriptException {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript indexScript = resolver.locateScript(pkg, List.of("index"));
        ResolvedScript utilsScript = resolver.locateScript(pkg, List.of("utils"));

        ScriptModuleHandle index = engine.loadModule(indexScript);
        ScriptModuleHandle utils = engine.loadModule(utilsScript);

        assertSame(utils, engine.getLoadedModule("utils.js"));
        // Cached module should have the same PI value
        assertEquals(3.14159, asDouble(utils.getExport("PI")), 1e-5);
    }

    // --- builtin module tests ---

    @Test
    public void shouldRequireBuiltinAndVerifyValues() throws Exception {
        builtinRegistry.register("math", () -> Map.of("PI", 3.14159265358979, "E", 2.71828182845905));
        loadTestPackage();

        var requireFunc = createRequireResolver();
        ScriptModuleHandle handle = requireFunc.resolve("math", "test.js");

        assertEquals(2, handle.getExportNames().size());
        assertEquals(3.14159265358979, asDouble(handle.getExport("PI")), 1e-10);
        assertEquals(2.71828182845905, asDouble(handle.getExport("E")), 1e-10);
    }

    @Test
    public void shouldBuiltinTakePriorityOverPackage() throws Exception {
        builtinRegistry.register("mylib", () -> Map.of("source", "builtin", "value", 999));
        loadTestPackage();

        var requireFunc = createRequireResolver();
        ScriptModuleHandle handle = requireFunc.resolve("mylib", "test.js");

        assertEquals("builtin", handle.getExport("source").asString());
        assertEquals(2, handle.getExportNames().size());
        assertFalse(handle.getExportNames().contains("PI")); // not from package
    }

    @Test
    public void shouldResolveBuiltinStringAndNumberValues() throws Exception {
        builtinRegistry.register("config", () -> Map.of(
            "host", "localhost",
            "port", 8080,
            "debug", true
        ));

        var requireFunc = createRequireResolver();
        ScriptModuleHandle handle = requireFunc.resolve("config", "test.js");

        assertEquals("localhost", handle.getExport("host").asString());
        assertEquals(8080.0, asDouble(handle.getExport("port")), 1e-10);
    }

    // --- executeEntry test ---

    @Test
    public void shouldExecuteEntryScript() throws Exception {
        ResolvedPackage pkg = loadTestPackage();
        ResolvedScript script = resolver.locateScript(pkg, List.of("index"));
        assertNotNull(script);

        try (var is = script.open()) {
            engine.executeEntry("index", is);
        }
        // executeEntry doesn't return a handle; just verify it doesn't throw
    }

    @Test
    public void shouldResolveJsExtension() {
        assertEquals("js", resolver.getExtension());
    }
}
