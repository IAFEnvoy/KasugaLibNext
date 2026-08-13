package lib.kasuga.slp.javet.module;

import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.module.PackageRegistry;
import lib.kasuga.scripting.module.ResolvedPackage;
import lib.kasuga.scripting.module.ResolvedScript;
import lib.kasuga.scripting.module.ScriptModuleHandle;
import lib.kasuga.slp.javet.JavetScriptEngine;

import java.util.List;

/**
 * Production require branch for package-by-name and in-package relative modules. Plugged into
 * {@link JavetScriptEngine}'s require resolver after the builtin branches ({@code kasuga:timer} and
 * Supplier builtins), this is what lets a production script {@code require("./utils")} or
 * {@code require("@my/pkg")} — the two branches the previous setup only had in unit-test wiring.
 *
 * <p>Relative resolution ({@code ./x}, {@code ../x}) is owner-scoped: the engine tracks which
 * {@link ResolvedPackage} each loaded module/entry belongs to, so {@code ./utils} from
 * {@code scripts/index.js} resolves against <em>that</em> package's resource root. For the entry's first
 * frame (no prior load) the owner comes from the 3-arg {@code executeEntry}; if still unknown it falls back
 * to scanning all packages — correct for the common single-package-engine case, ambiguous if two packages
 * each have a file at the same relative path.
 */
public final class PackageAwareRequireResolver implements RequireResolver {

    private final JavetScriptEngine engine;
    private final PackageRegistry packageRegistry;
    private final JsModuleResolver resolver;

    public PackageAwareRequireResolver(JavetScriptEngine engine, PackageRegistry packageRegistry, JsModuleResolver resolver) {
        this.engine = engine;
        this.packageRegistry = packageRegistry;
        this.resolver = resolver;
    }

    @Override
    public ScriptModuleHandle resolve(String moduleName, String fromSourcePath) throws ScriptException {
        if (moduleName == null || moduleName.isEmpty()) {
            return null;
        }

        // Package-by-name: require("@my/pkg") → the package's main entry.
        ResolvedPackage byName = packageRegistry.lookup(moduleName);
        if (byName != null) {
            ResolvedScript script = resolver.locateScript(byName, List.of());
            if (script != null) {
                return engine.loadModule(script);
            }
        }

        // In-package relative: require("./utils") / require("../utils").
        if (moduleName.startsWith("./") || moduleName.startsWith("../")) {
            String dir = fromSourcePath != null && fromSourcePath.contains("/")
                    ? fromSourcePath.substring(0, fromSourcePath.lastIndexOf('/'))
                    : "";
            String target = dir.isEmpty()
                    ? moduleName.substring(2)
                    : dir + "/" + moduleName.substring(2);
            if (target.isEmpty()) {
                // require("./") → owning package root (index.js).
                ResolvedPackage owner = engine.getOwningPackage(fromSourcePath);
                if (owner != null) {
                    ResolvedScript script = resolver.locateScript(owner, List.of());
                    if (script != null) {
                        return engine.loadModule(script);
                    }
                }
                return null;
            }
            List<String> segments = List.of(target.split("/"));

            // Owner-scoped first (the common, unambiguous path).
            ResolvedPackage owner = engine.getOwningPackage(fromSourcePath);
            if (owner != null) {
                ResolvedScript script = resolver.locateScript(owner, segments);
                if (script != null) {
                    return engine.loadModule(script);
                }
            }
            // Fallback: scan all packages (entry-point first frame or cross-package require). Deterministic
            // by insertion order; ambiguous if multiple packages expose the same relative path.
            for (ResolvedPackage candidate : packageRegistry.all().values()) {
                ResolvedScript script = resolver.locateScript(candidate, segments);
                if (script != null) {
                    return engine.loadModule(script);
                }
            }
        }

        return null;
    }
}
