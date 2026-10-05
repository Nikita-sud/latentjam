import ai.onnxruntime.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Standalone Android JNI probe, run with app_process; never installed into LatentJam. */
public final class OrtParity {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        System.out.println("version\t" + env.getVersion());
        System.out.println("providers\t" + OrtEnvironment.getAvailableProviders());
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            // The music encoder needs LatentJam's own operator: libljq4.so (core/ort-ops) beside the fixtures.
            Path operators = root.resolve("libljq4.so");
            if (Files.exists(operators)) options.registerCustomOpLibrary(operators.toString());
            boolean invalidRejected = false;
            try (OrtSession ignored = env.createSession(new byte[]{1,2,3,4}, options)) {
                throw new IllegalStateException("Corrupt model unexpectedly loaded");
            } catch (OrtException expected) { invalidRejected = true; }
            System.out.println("corrupt_model_rejected\t" + invalidRejected);
            Map<String, OrtSession> sessions = new LinkedHashMap<>();
            try {
                List<String> lines = Files.readAllLines(root.resolve("fixtures/manifest.tsv"));
                for (int line = 0; line < lines.size();) {
                    String[] header = lines.get(line++).split("\t");
                    String id = header[0], model = header[1];
                    int inputCount = Integer.parseInt(header[2]);
                    OrtSession session = sessions.get(model);
                    if (session == null) {
                        long started = System.nanoTime();
                        session = env.createSession(Files.readAllBytes(root.resolve("models/" + model)), options);
                        sessions.put(model, session);
                        System.out.println("load_ms\t" + model + "\t" + (System.nanoTime()-started)/1e6);
                    }
                    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                    try {
                        for (int j = 0; j < inputCount; ++j) {
                            String[] spec = lines.get(line++).split("\t");
                            long[] shape = Arrays.stream(spec[2].split(",")).mapToLong(Long::parseLong).toArray();
                            byte[] bytes = Files.readAllBytes(root.resolve("fixtures/" + spec[3]));
                            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
                            buffer.put(bytes); buffer.rewind();
                            inputs.put(spec[0], spec[1].equals("float")
                                ? OnnxTensor.createTensor(env, buffer.asFloatBuffer(), shape)
                                : OnnxTensor.createTensor(env, buffer.asLongBuffer(), shape));
                        }
                        long[] times = new long[6];
                        for (int rep = 0; rep < times.length; ++rep) {
                            long started = System.nanoTime();
                            try (OrtSession.Result result = session.run(inputs)) {
                                times[rep] = System.nanoTime() - started;
                                if (rep == 0) for (int j = 0; j < result.size(); ++j) {
                                    FloatBuffer values = ((OnnxTensor)result.get(j)).getFloatBuffer();
                                    ByteBuffer bytes = ByteBuffer.allocate(values.remaining()*4).order(ByteOrder.LITTLE_ENDIAN);
                                    while (values.hasRemaining()) bytes.putFloat(values.get());
                                    Files.write(out.resolve(id + "." + j + ".f32"), bytes.array());
                                }
                            }
                        }
                        long[] warmed = Arrays.copyOfRange(times, 1, times.length);
                        Arrays.sort(warmed);
                        System.out.println("case_ms\t" + id + "\t" + warmed[2]/1e6);
                    } finally { for (OnnxTensor tensor : inputs.values()) tensor.close(); }
                }
                // Exercise native -> JNI -> Java error propagation and recovery after invalid shape.
                OrtSession semantic = sessions.get("universal_semantic_head.onnx");
                try (OnnxTensor wrong = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[3]), new long[]{1,3})) {
                    boolean rejected = false;
                    try (OrtSession.Result ignored = semantic.run(Collections.singletonMap("embedding", wrong))) {
                        throw new IllegalStateException("Invalid shape unexpectedly accepted");
                    } catch (OrtException expected) { rejected = true; }
                    System.out.println("invalid_shape_rejected\t" + rejected);
                }
                try (OnnxTensor valid = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[960]), new long[]{1,960});
                     OrtSession.Result ignored = semantic.run(Collections.singletonMap("embedding", valid))) {
                    System.out.println("recovery_after_error\ttrue");
                }
            } finally { for (OrtSession session : sessions.values()) session.close(); }
        }
    }
}
