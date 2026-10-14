import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import com.google.gson.*;
import io.github.nikitasud.latentjam.smart.*;
import io.github.nikitasud.latentjam.smart.cluster.*;

/** Exact production centering and k-means on a subset, without Android. */
public class StabilityProbe {
 public static void main(String[] args)throws Exception {
  byte[] b=Files.readAllBytes(Path.of(args[0]));int d=1344,n=b.length/(d*4);
  if(b.length!=n*d*4)throw new IllegalArgumentException();
  float[] x=new float[n*d];ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(x);
  Method box=TrackId.class.getMethod("box-impl",String.class);
  List<TrackId> ids=new ArrayList<>();Map<TrackId,Integer> positions=new HashMap<>();
  for(int i=0;i<n;i++){TrackId id=(TrackId)box.invoke(null,"row-"+i);ids.add(id);positions.put(id,i);}
  long started=System.nanoTime();
  List<TrackCluster> clusters=TrackClustering.INSTANCE.cluster$smart(new LibraryVectorSpace(ids,x.clone(),d,LibraryVectorSource.AUDIO_AND_METADATA,0L),LibraryWorlds.INSTANCE.recommendedK$smart(n),4,20);
  double seconds=(System.nanoTime()-started)/1e9;
  int[] labels=new int[n];Arrays.fill(labels,-1);
  for(int j=0;j<clusters.size();j++)for(TrackId id:clusters.get(j).getMembers())labels[positions.get(id)]=j;
  TrackClustering.INSTANCE.center$smart(x,n,d);
  ByteBuffer out=ByteBuffer.allocate(b.length).order(ByteOrder.LITTLE_ENDIAN);out.asFloatBuffer().put(x);Files.write(Path.of(args[1]),out.array());
  Files.writeString(Path.of(args[2]),new Gson().toJson(Map.of("labels",labels,"seconds",seconds)));
 }
}
