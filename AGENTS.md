# Agent Notes

## Modelling module unit tests

Run the pure-Java modelling tests (no NeoForge/FML runtime) with:

```bash
JAVA_HOME=/Users/vfyjxf/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home \
  ./gradlew :modules:modelling:modelUnitTest --no-daemon
```

The default system JVM on this machine is Java 25 (JBR-25). Running Gradle with
Java 25 causes `:gradle-plugin:compileGroovy` to fail because Groovy cannot read
class file major version 69. Use a Java 21 JDK for the Gradle daemon instead.
