import java.nio.*;
import java.nio.file.*;
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout;
import io.github.nikitasud.latentjam.smart.cluster.Tsne;

/** Unmodified Kotlin PCA/t-SNE on an explicit research matrix, without phone access. */
public class MapProjectionProbe {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        int dim = Integer.parseInt(args[2]);
        byte[] data = Files.readAllBytes(input);
        if (data.length % (dim * 4) != 0) throw new IllegalArgumentException("Matrix shape");
        float[] rows = new float[data.length / 4];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rows);
        int n = rows.length / dim;
        long start = System.nanoTime();
        float[] reduced = LibraryLayout.INSTANCE.reduceForEmbedding$smart(rows, n, dim, () -> true);
        float[] coordinates = Tsne.INSTANCE.embed(reduced, n, reduced.length / n, 0x1A7E27, null, () -> true);
        double elapsed = (System.nanoTime() - start) / 1e9;
        ByteBuffer result = ByteBuffer.allocate(coordinates.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        result.asFloatBuffer().put(coordinates);
        Files.write(output, result.array());
        System.out.printf(java.util.Locale.ROOT, "{\"rows\":%d,\"input_dim\":%d,\"seconds\":%.6f}%n", n, dim, elapsed);
    }
}
