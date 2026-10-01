import json
from pathlib import Path

from ..fileutil import atomic_write
from . import Index, merge


class JsonIndex(Index):
    """datamarts/inverted_index.json: un único objeto
    {"adventure":[5,12,42],"island":[5,1342]} con las claves ordenadas y sin
    espacios. Actualizarlo es cargar, fusionar y reescribir el fichero entero."""

    name = "json"

    def __init__(self, path: Path):
        self.path = path
        self._postings: dict[str, list[int]] | None = None  # None hasta la primera carga

    def _load(self) -> dict[str, list[int]]:
        if self._postings is None:
            try:
                self._postings = json.loads(self.path.read_bytes())
            except FileNotFoundError:
                self._postings = {}
        return self._postings

    def add(self, postings):
        index = self._load()
        for term, ids in postings.items():
            index[term] = merge(index.get(term, []), ids)
        data = json.dumps(index, sort_keys=True, separators=(",", ":"))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write(self.path, data.encode("ascii"))

    def lookup(self, term):
        return self._load().get(term, [])

    def each(self):
        index = self._load()
        for term in sorted(index):
            yield term, index[term]

    def empty(self):
        return not self._load()

    def disk_usage(self):
        try:
            return self.path.stat().st_size
        except FileNotFoundError:
            return 0

    def reset(self):
        self.path.unlink(missing_ok=True)
        self._postings = {}
