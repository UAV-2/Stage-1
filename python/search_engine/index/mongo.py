from . import Index

DATABASE = "search_engine"
TIMEOUT_MS = 5000


class MongoIndex(Index):
    """Colección inverted_index_<lang> de la base search_engine, con documentos
    {"term": "adventure", "postings": [5, 12, 42]} y un índice único en term.
    Los postings se guardan como conjunto y se ordenan al leer."""

    name = "mongo"

    def __init__(self, uri: str, lang: str):
        # Import diferido: pymongo solo hace falta para esta variante.
        import pymongo
        from pymongo.errors import PyMongoError

        self._pymongo = pymongo
        self.client = pymongo.MongoClient(uri, serverSelectionTimeoutMS=TIMEOUT_MS)
        try:
            self.client.admin.command("ping")
            self.collection = self.client[DATABASE][f"inverted_index_{lang}"]
            self._ensure_term_index()
        except PyMongoError as e:
            self.client.close()
            raise OSError(f"no se puede conectar a MongoDB en {uri} (¿está levantado?): {e}") from None

    def _ensure_term_index(self):
        self.collection.create_index("term", unique=True)

    def add(self, postings):
        if not postings:
            return
        updates = [
            self._pymongo.UpdateOne({"term": term}, {"$addToSet": {"postings": {"$each": ids}}}, upsert=True)
            for term, ids in postings.items()
        ]
        self.collection.bulk_write(updates, ordered=False)

    def lookup(self, term):
        entry = self.collection.find_one({"term": term})
        return sorted(entry["postings"]) if entry else []

    def each(self):
        for entry in self.collection.find().sort("term", 1):
            yield entry["term"], sorted(entry["postings"])

    def empty(self):
        return self.collection.estimated_document_count() == 0

    def disk_usage(self):
        # storageSize de la colección: los datos comprimidos en el servidor, sin
        # el índice sobre term. Antes se fuerza un checkpoint, porque WiredTiger
        # tarda hasta un minuto en volcar a disco lo último escrito.
        self.client.admin.command("fsync")
        stats = self.collection.database.command("collStats", self.collection.name)
        return int(stats["storageSize"])

    def reset(self):
        self.collection.drop()
        self._ensure_term_index()

    def close(self):
        self.client.close()
