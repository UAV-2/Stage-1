import os
import shutil
from pathlib import Path

from ..fileutil import atomic_write, tree_stats
from . import Index, merge

SUFFIX = ".txt"


class FolderIndex(Index):
    """datamarts/inverted_index/<LETRA>/<término>.txt, con un ID por línea. La
    subcarpeta es la primera letra del término en mayúscula. Actualizarlo solo
    reescribe los ficheros de los términos afectados."""

    name = "folders"

    def __init__(self, root: Path):
        self.root = root

    def _path(self, term: str) -> Path:
        return self.root / term[0].upper() / f"{term}{SUFFIX}"

    def add(self, postings):
        created = set()
        for term, ids in postings.items():
            existing = self.lookup(term)
            path = self._path(term)
            if path.parent not in created:
                path.parent.mkdir(parents=True, exist_ok=True)
                created.add(path.parent)
            content = "".join(f"{book_id}\n" for book_id in merge(existing, ids))
            # Atómica: un fichero cortado a mitad dejaría un ID truncado que
            # parecería un posting válido.
            atomic_write(path, content.encode("ascii"))

    def lookup(self, term):
        try:
            return [int(line) for line in self._path(term).read_bytes().split()]
        except FileNotFoundError:
            return []

    def each(self):
        # Las carpetas A..Z se recorren en orden; dentro de cada una se ordena
        # por término, no por nombre de fichero.
        try:
            letters = sorted(entry.name for entry in os.scandir(self.root) if entry.is_dir())
        except FileNotFoundError:
            return
        for letter in letters:
            names = os.listdir(self.root / letter)
            for term in sorted(name.removesuffix(SUFFIX) for name in names if name.endswith(SUFFIX)):
                yield term, self.lookup(term)

    def empty(self):
        try:
            return not os.listdir(self.root)
        except FileNotFoundError:
            return True

    def disk_usage(self):
        # Suma el tamaño de los ficheros; el espacio real es mayor, porque cada
        # fichero ocupa al menos un bloque del sistema de ficheros.
        return tree_stats(self.root)[2]

    def reset(self):
        shutil.rmtree(self.root, ignore_errors=True)
