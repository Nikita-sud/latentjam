import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import io.github.nikitasud.latentjam.smart.text.*;

/** Uses the shipped Kotlin metadata builder, artist facts and WordPiece tokenizer. */
public class MpdTextInputs {
    static AssetBytes asset(Path p) { try { return AssetBytes.Companion.of(Files.readAllBytes(p)); } catch(Exception e) { throw new RuntimeException(e); } }
    static String str(JsonObject row, String key) { return !row.has(key) || row.get(key).isJsonNull() ? null : row.get(key).getAsString(); }
    static Integer num(JsonObject row, String key) { return !row.has(key) || row.get(key).isJsonNull() ? null : row.get(key).getAsInt(); }
    public static void main(String[] args) throws Exception {
        Path assets = Path.of(args[0]);
        MusicEntityResolver resolver = new MusicEntityResolver(() -> asset(assets.resolve("music_entities_250k.bin")));
        ArtistKnowledge knowledge = new ArtistKnowledge(resolver, () -> null, () -> asset(assets.resolve("artist_knowledge.bin")));
        Map<String,Integer> vocab = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(assets.resolve("text_vocab.txt"));
        for (int i=0;i<lines.size();i++) vocab.put(lines.get(i), i);
        BertWordPieceTokenizer tokenizer = new BertWordPieceTokenizer(vocab,"[UNK]","[CLS]","[SEP]",100,48);
        JsonArray input = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        JsonArray output = new JsonArray();
        int factsCount = 0;
        for (JsonElement e : input) {
            JsonObject row = e.getAsJsonObject();
            String artist = str(row,"artist");
            ArtistFacts facts = knowledge.facts(artist,artist);
            if (facts != null) factsCount++;
            String text = TextEncoder.Companion.metadataString(str(row,"genre"),artist,str(row,"title"),num(row,"year"),num(row,"original_year"),str(row,"language"),facts == null ? null : facts.getLanguage(),facts == null ? null : facts.getDecade());
            JsonObject out = new JsonObject();
            out.addProperty("track_id",str(row,"track_id")); out.addProperty("text",text);
            out.addProperty("artist_facts",facts != null);
            JsonArray ids = new JsonArray(); for (int id : tokenizer.encode(text)) ids.add(id);
            out.add("tokens",ids); output.add(out);
        }
        Files.writeString(Path.of(args[2]), new Gson().toJson(output));
        System.out.printf("{\"rows\":%d,\"artist_facts_rows\":%d}%n",input.size(),factsCount);
    }
}
