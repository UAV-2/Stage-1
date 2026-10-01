package es.ulpgc.bigdata.index;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Colección inverted_index_&lt;lang&gt; de la base search_engine, con documentos
 * {"term": "adventure", "postings": [5, 12, 42]} y un índice único en term. Los postings
 * se guardan como conjunto y se ordenan al leer.
 */
public class MongoIndex implements InvertedIndex {

    private static final String DATABASE = "search_engine";
    private static final long TIMEOUT_SECONDS = 5;

    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoIndex(String uri, String lang) throws IOException {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .build();
        client = MongoClients.create(settings);
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            collection = client.getDatabase(DATABASE).getCollection("inverted_index_" + lang);
            ensureTermIndex();
        } catch (MongoException e) {
            client.close();
            throw new IOException("no se puede conectar a MongoDB en " + uri + " (¿está levantado?): "
                    + e.getMessage(), e);
        }
    }

    private void ensureTermIndex() {
        collection.createIndex(Indexes.ascending("term"), new IndexOptions().unique(true));
    }

    @Override
    public String name() {
        return "mongo";
    }

    @Override
    public void add(Map<String, List<Integer>> postings) throws IOException {
        if (postings.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> updates = new ArrayList<>(postings.size());
        postings.forEach((term, ids) -> updates.add(new UpdateOneModel<>(
                Filters.eq("term", term),
                Updates.addEachToSet("postings", ids),
                new UpdateOptions().upsert(true))));
        try {
            collection.bulkWrite(updates, new BulkWriteOptions().ordered(false));
        } catch (MongoException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static List<Integer> sortedPostings(Document entry) {
        List<Integer> ids = new ArrayList<>();
        for (Object value : entry.getList("postings", Object.class, List.of())) {
            ids.add(((Number) value).intValue());
        }
        ids.sort(null);
        return ids;
    }

    @Override
    public List<Integer> lookup(String term) throws IOException {
        try {
            Document entry = collection.find(Filters.eq("term", term)).first();
            return entry == null ? List.of() : sortedPostings(entry);
        } catch (MongoException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void forEach(TermVisitor visitor) throws IOException {
        try (MongoCursor<Document> cursor = collection.find().sort(Sorts.ascending("term")).iterator()) {
            while (cursor.hasNext()) {
                Document entry = cursor.next();
                visitor.visit(entry.getString("term"), sortedPostings(entry));
            }
        } catch (MongoException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isEmpty() throws IOException {
        try {
            return collection.estimatedDocumentCount() == 0;
        } catch (MongoException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void reset() throws IOException {
        try {
            collection.drop();
            ensureTermIndex();
        } catch (MongoException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
