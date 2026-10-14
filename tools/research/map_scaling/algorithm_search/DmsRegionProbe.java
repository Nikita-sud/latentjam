import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.lang.reflect.*;
import com.google.gson.*;
import kotlin.Pair;
import io.github.nikitasud.latentjam.smart.*;
import io.github.nikitasud.latentjam.smart.cluster.*;
import io.github.nikitasud.latentjam.library.*;
import io.github.nikitasud.latentjam.library.tags.TextRepair;

public class DmsRegionProbe {
 static Gson gson=new GsonBuilder().setPrettyPrinting().create();
 static Method box, unbox;
 static TrackId id(String s)throws Exception{return (TrackId)box.invoke(null,s);}
 static String str(JsonObject o,String k){return !o.has(k)||o.get(k).isJsonNull()?null:o.get(k).getAsString();}
 static Integer integer(JsonObject o,String k){String s=str(o,k);return s==null||s.isEmpty()?null:Integer.valueOf(s);}
 static Long lng(JsonObject o,String k){String s=str(o,k);return s==null||s.isEmpty()?null:Long.valueOf(s);}
 static String clean(String s){return s==null||s.isBlank()||s.equals("<unknown>")?null:TextRepair.INSTANCE.repair(s);}
 static String utf(DataInputStream in)throws Exception {int n=in.readInt();if(n==-1)return null;if(n<0||n>1000000)throw new IOException("bad string");return new String(in.readNBytes(n),StandardCharsets.UTF_8);}
 static Map<TrackId,float[]> index(Path p,int expected)throws Exception{
  Map<TrackId,float[]> result=new LinkedHashMap<>();
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))){
   if(in.readInt()!=0x4c4a4958)throw new IOException("magic");int v=in.readInt();if(v!=3)throw new IOException("version");utf(in);int d=in.readInt(),n=in.readInt();if(d!=expected)throw new IOException("dim");
   for(int i=0;i<n;i++){String key=utf(in);float[] r=new float[d];for(int j=0;j<d;j++){r[j]=in.readFloat();if(!Float.isFinite(r[j]))throw new IOException("nonfinite");}utf(in);if(result.put(id(key),r)!=null)throw new IOException("duplicate");}
   int f=in.readInt();for(int i=0;i<f;i++){utf(in);utf(in);}if(in.read()!=-1)throw new IOException("trailing bytes");
  }return result;
 }
 static Map<String,Object> obj(Object... p){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<p.length;i+=2)m.put((String)p[i],p[i+1]);return m;}
 static void floats(Path path,float[] x)throws Exception {ByteBuffer b=ByteBuffer.allocate(x.length*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(x);Files.write(path,b.array());}
 static String unmangle(String s,String clean){return clean!=null&&TextRepair.INSTANCE.isMangling(s,clean)?clean:s;}
 public static void main(String[] args)throws Exception{
  Path p=Path.of(args[0]), inputPath=p.resolve("regions"), out=Path.of(args[1]), snapshot=p.resolve("snapshot");Files.createDirectories(out);
  JsonObject experiment=JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();
  List<String> selected=new ArrayList<>();for(JsonElement v:experiment.getAsJsonArray("ids"))selected.add(v.getAsString());Set<String> selectedSet=new HashSet<>(selected);
  box=TrackId.class.getMethod("box-impl",String.class);unbox=TrackDescriptor.class.getMethod("getId-tOAX0DY");
  JsonObject input=JsonParser.parseString(Files.readString(inputPath.resolve("private-input.json"))).getAsJsonObject();
  List<TrackDescriptor> library=new ArrayList<>();List<TrackId> ids=new ArrayList<>();Map<String,Integer> rowIndex=new HashMap<>();List<Map<String,Object>> cooked=new ArrayList<>();
  Constructor<?> ctor=Arrays.stream(TrackDescriptor.class.getConstructors()).filter(c->c.getParameterCount()==25).findFirst().orElseThrow();
  int row=0;
  for(JsonElement e:input.getAsJsonArray("tracks")){
   JsonObject m=e.getAsJsonObject();String key=str(m,"id");if(!selectedSet.contains(key))continue;Object[] f=new Object[25];f[0]=key;int mask=(1<<23)-1;mask&=~1;
   String[] keys={"title","artist","album","genre"};for(int i=0;i<keys.length;i++){f[i+1]=clean(str(m,keys[i]));mask&=~(1<<(i+1));}
   if(m.has("enriched")&&!m.get("enriched").isJsonNull()){
    JsonObject en=m.getAsJsonObject("enriched");String genres=clean(str(en,"genre"));if(genres!=null){List<String> parts=new ArrayList<>();for(String g:genres.split("; ",-1))parts.add(unmangle(g,(String)f[4]));f[4]=String.join("; ",parts);}
    List<String> artists=new ArrayList<>();for(JsonElement a:en.getAsJsonArray("artists"))artists.add(unmangle(TextRepair.INSTANCE.repair(a.getAsString()),(String)f[2]));f[13]=artists;mask&=~(1<<13);
    f[14]=integer(en,"originalYear");mask&=~(1<<14);
    f[20]=clean(str(en,"albumArtist"));if(f[20]!=null)f[20]=unmangle((String)f[20],clean(str(m,"albumArtist")));else f[20]=clean(str(m,"albumArtist"));mask&=~(1<<20);
    f[10]=integer(m,"year");if(f[10]==null)f[10]=integer(en,"year");mask&=~(1<<10);
   }else {f[10]=integer(m,"year");mask&=~(1<<10);f[20]=clean(str(m,"albumArtist"));mask&=~(1<<20);}
   f[5]=lng(m,"durationMs");mask&=~(1<<5);f[9]=str(m,"folderPath");mask&=~(1<<9);f[12]=str(m,"sourceRevision");mask&=~(1<<12);
   f[18]=integer(m,"trackNumber");mask&=~(1<<18);f[19]=integer(m,"discNumber");mask&=~(1<<19);f[21]=str(m,"albumArtworkUri");mask&=~(1<<21);
   f[23]=mask;TrackDescriptor d=(TrackDescriptor)ctor.newInstance(f);library.add(d);ids.add(id(key));rowIndex.put(key,row++);
   cooked.add(obj("id",key,"title",d.getTitle(),"artist",d.getArtist(),"album",d.getAlbum(),"genre",d.getGenre(),"year",d.getYear(),"originalYear",d.getOriginalYear(),"genreFamilies",Genres.INSTANCE.families(d.getGenre())));
  }
  Files.writeString(out.resolve("private-cooked-metadata.json"),gson.toJson(cooked));
  if(!ids.stream().map(TrackId::getValue).toList().equals(selected))throw new IllegalStateException("Row order mismatch");
  Map<TrackId,TrackSemantics> sem=new HashMap<>();JsonObject semantic=JsonParser.parseString(Files.readString(inputPath.resolve("private-semantics.json"))).getAsJsonObject();
  for(Map.Entry<String,JsonElement> e:semantic.entrySet()){JsonArray a=e.getValue().getAsJsonArray();float[] vals=new float[a.size()];for(int i=0;i<a.size();i++)vals[i]=a.get(i).getAsFloat();TrackSemantics s=TrackSemantics.Companion.fromModelOutput(vals);if(s!=null)sem.put(id(e.getKey()),s);}
  List<Pair<String,Collection<TrackId>>> playlists=new ArrayList<>();for(JsonElement e:input.getAsJsonArray("playlists")){JsonObject pl=e.getAsJsonObject();List<TrackId> mem=new ArrayList<>();for(JsonElement tid:pl.getAsJsonArray("ids"))mem.add(id(tid.getAsString()));playlists.add(new Pair<>(str(pl,"name"),mem));}
  List<Pair<String,List<TrackDescriptor>>> albums=new ArrayList<>();for(AlbumGroup a:LibraryCatalog.Companion.build(library,null).getAlbums())albums.add(new Pair<>(a.getTitle(),a.getTracks()));
  WorldNamingGroups naming=WorldNamingGroups.Companion.of(new HashSet<>(ids),playlists,albums);
  List<Map<String,Object>> groupDump=new ArrayList<>();for(Pair<String,Set<TrackId>> pl:naming.getPlaylists())groupDump.add(obj("name",pl.getFirst(),"members",pl.getSecond().stream().map(x->rowIndex.get(x.getValue())).sorted().toList()));
  Files.writeString(out.resolve("private-playlists.json"),gson.toJson(groupDump));
  List<Map<String,Object>> albumDump=new ArrayList<>();for(Pair<String,Set<TrackId>> al:naming.getAlbums())albumDump.add(obj("name",al.getFirst(),"members",al.getSecond().stream().map(x->rowIndex.get(x.getValue())).sorted().toList()));
  Files.writeString(out.resolve("private-albums.json"),gson.toJson(albumDump));
  for(JsonElement item:experiment.getAsJsonArray("cases")) {
   JsonObject variant=item.getAsJsonObject();String label=variant.get("name").getAsString();int dim=variant.get("dim").getAsInt();
   byte[] bytes=Files.readAllBytes(Path.of(variant.get("input").getAsString()));
   if(bytes.length!=ids.size()*dim*4)throw new IllegalArgumentException("Matrix size");
   float[] values=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
   for(float value:values)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite matrix");
   LibraryVectorSource sourceKind=LibraryVectorSource.AUDIO_AND_METADATA;
   if(variant.has("metadata_weight")) {
    Map<TrackId,float[]> audio=new LinkedHashMap<>(),metadata=new LinkedHashMap<>();
    for(int block=0;block<2;block++) {
     int width=block==0?960:384;String file=block==0?"audio.f32":"metadata.f32";
     byte[] data=Files.readAllBytes(inputPath.resolve(file));
     if(data.length!=ids.size()*width*4)throw new IllegalArgumentException("Full-library source matrix size");
     float[] matrix=new float[data.length/4];ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(matrix);
     for(int i=0;i<ids.size();i++) {
      float[] v=Arrays.copyOfRange(matrix,i*width,(i+1)*width);double norm=0;
      for(float f:v){if(!Float.isFinite(f))throw new IllegalArgumentException("Nonfinite source");norm+=f*f;}
      if(norm>1e-6)(block==0?audio:metadata).put(ids.get(i),v);
     }
    }
    values=LibraryVectorFusion.INSTANCE.build(ids,audio,metadata,960,384,variant.get("metadata_weight").getAsFloat(),.8f).peekRows$smart().clone();
    if(values.length!=ids.size()*dim)throw new IllegalStateException("Hybrid shape");
   }
   if(variant.has("audio_only") && variant.get("audio_only").getAsBoolean()) {
    if(dim!=960)throw new IllegalArgumentException("Expected shipped audio dimension");
    Map<TrackId,float[]> audio=new LinkedHashMap<>();for(int i=0;i<ids.size();i++)audio.put(ids.get(i),Arrays.copyOfRange(values,i*dim,(i+1)*dim));
    values=LibraryVectorFusion.INSTANCE.build(ids,audio,Collections.emptyMap(),960,384,.75f,.8f).peekRows$smart().clone();
    sourceKind=LibraryVectorSource.AUDIO;
   }
   floats(out.resolve("private-"+label+"-used-input.f32"),values);
   LibraryVectorSpace space=new LibraryVectorSpace(ids,values.clone(),dim,sourceKind,0L);
   LibraryVectorSpace clusterSpace=new LibraryVectorSpace(ids,values.clone(),dim,sourceKind,0L);
   int k=variant.has("k")?variant.get("k").getAsInt():LibraryWorlds.INSTANCE.recommendedK$smart(ids.size());
   long started=System.nanoTime();
   List<TrackCluster> raw;
   List<LibraryWorld> discovered;
   if(variant.has("assignments")) {
    JsonArray lab=JsonParser.parseString(Files.readString(Path.of(variant.get("assignments").getAsString()))).getAsJsonArray();
    if(lab.size()!=ids.size())throw new IllegalArgumentException("Assignment count");
    int[] labels=new int[lab.size()];for(int i=0;i<labels.length;i++){labels[i]=lab.get(i).getAsInt();if(labels[i]<-1)throw new IllegalArgumentException("Invalid assignment");}
    float[] centered=values.clone();TrackClustering.INSTANCE.center$smart(centered,ids.size(),dim);
    Method collect=TrackClustering.class.getDeclaredMethod("collect",float[].class,int.class,int.class,int[].class,List.class,int.class);collect.setAccessible(true);
    int kept=0;for(int l:labels)if(l>=0)kept++;
    float[] cx=new float[kept*dim];int[] cl=new int[kept];List<TrackId> ci=new ArrayList<>();Map<Integer,Integer> compact=new HashMap<>();
    int offset=0;for(int i=0;i<labels.length;i++)if(labels[i]>=0){System.arraycopy(centered,i*dim,cx,offset*dim,dim);cl[offset]=compact.computeIfAbsent(labels[i],z->compact.size());ci.add(ids.get(i));offset++;}
    raw=kept==0?new ArrayList<>(): (List<TrackCluster>)collect.invoke(TrackClustering.INSTANCE,cx,kept,dim,cl,ci,4);
    if(variant.has("without_confidence")&&variant.get("without_confidence").getAsBoolean()) {
     List<TrackCluster> untrimmed=new ArrayList<>();for(TrackCluster c:raw)untrimmed.add(new TrackCluster(c.getMembers(),Collections.emptyList()));raw=untrimmed;
    }
    Method worldsMethod=LibraryWorlds.class.getDeclaredMethod("worlds",List.class,List.class,int.class,Map.class);worldsMethod.setAccessible(true);
    discovered=(List<LibraryWorld>)worldsMethod.invoke(LibraryWorlds.INSTANCE,library,raw,4,sem);
   } else {
    raw=TrackClustering.INSTANCE.cluster$smart(clusterSpace,k,4,20);
    if(variant.has("without_confidence")&&variant.get("without_confidence").getAsBoolean()){List<TrackCluster> u=new ArrayList<>();for(TrackCluster c:raw)u.add(new TrackCluster(c.getMembers(),Collections.emptyList()));raw=u;}
    Method wm=LibraryWorlds.class.getDeclaredMethod("worlds",List.class,List.class,int.class,Map.class);wm.setAccessible(true);
    discovered=(List<LibraryWorld>)wm.invoke(LibraryWorlds.INSTANCE,library,raw,4,sem);
   }
   long processingNanos=System.nanoTime()-started;
   List<LibraryWorld> named=LibraryWorlds.INSTANCE.namedAfterGroups(discovered,naming,.45);
   List<Map<String,Object>> worlds=new ArrayList<>(),clusters=new ArrayList<>();
   for(TrackCluster c:raw)clusters.add(obj("members",c.getMembers().stream().map(x->rowIndex.get(x.getValue())).toList(),"margins",c.getMemberships().stream().map(x->x.getAssignmentMargin()).toList(),"similarities",c.getMemberships().stream().map(x->x.getCentroidSimilarity()).toList()));
   for(int ci=0;ci<named.size();ci++){
    LibraryWorld w=named.get(ci),d=discovered.get(ci);List<Integer> mem=new ArrayList<>();int supported=0;
    for(TrackDescriptor t:w.getTracks()){mem.add(rowIndex.get((String)unbox.invoke(t)));if(d.supportsName(t))supported++;}
    worlds.add(obj("name",w.getName(),"name_source",w.getNameSource(),"discovered_name",d.getName(),"discovered_name_source",d.getNameSource(),"content",w.getContent(),"blend_families",w.getBlendFamilies(),"members",mem,"members_support_discovered_name",supported));
   }
   Map<String,Object> report=obj("rows",ids.size(),"semantic_rows",sem.size(),"requested_k",k,"input_dim",dim,"worlds",worlds,"raw_clusters",clusters,"processing_nanos",processingNanos);
   Files.writeString(out.resolve("private-regions-"+label+".json"),gson.toJson(report));
   System.out.println(label+": rows="+ids.size()+" worlds="+worlds.size()+" assigned="+worlds.stream().mapToInt(x->((List<?>)x.get("members")).size()).sum());
  }
 }
}
