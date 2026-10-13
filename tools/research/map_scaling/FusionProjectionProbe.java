import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout;

/** Runs the shipped Kotlin preprojection on private inputs; never loads the phone. */
public class FusionProjectionProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        for (String name : new String[]{"joint-a25", "joint-a50", "separate-a25"}) {
            byte[] bytes = Files.readAllBytes(directory.resolve(name + "-input.f32"));
            if (bytes.length % (1344 * 4) != 0) throw new IllegalArgumentException("Invalid matrix length");
            float[] rows = new float[bytes.length / 4];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rows);
            int count = rows.length / 1344;
            float[] reduced = LibraryLayout.INSTANCE.reduceForEmbedding$smart(rows, count, 1344, () -> true);
            if (reduced.length != count * 50) throw new IllegalStateException("Unexpected projection shape");
            ByteBuffer result = ByteBuffer.allocate(reduced.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            result.asFloatBuffer().put(reduced);
            Files.write(directory.resolve(name + "-pca50.f32"), result.array());
            Files.writeString(directory.resolve(name + "-pca50.input-sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\n");
            System.out.println(name + ": " + count + " rows, 50 components");
        }
    }
}
