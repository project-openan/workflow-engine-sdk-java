# Build the examples (run from project root)
mvn compile
javac -cp target/classes -d examples/target examples/src/main/java/com/openan/a2at/engine/examples/*.java
echo "Build complete. Run with:"
echo "  java -cp target/classes:examples/target com.openan.a2at.engine.examples.ExecutePsopDemo"
echo "  java -cp target/classes:examples/target com.openan.a2at.engine.examples.WorkflowExecutorDemo"
