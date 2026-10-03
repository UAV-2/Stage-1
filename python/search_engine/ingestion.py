"""Obtiene el texto crudo de los libros y lo separa en cabecera y cuerpo (SPEC §4)."""

import gzip
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .fileutil import atomic_write, decode
from .text import trim_space

# Marcadores sin distinguir mayúsculas: hay libros antiguos con "THIS".
START = re.compile(r"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\n", re.IGNORECASE)
END = re.compile(r"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK", re.IGNORECASE)

URL_TEMPLATE = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"


class BookUnavailable(Exception):
    """Libro que no se puede ingerir (no existe o no tiene marcadores). Se
    descarta: reintentarlo daría el mismo resultado."""

    def __init__(self, message: str):
        super().__init__(f"libro no disponible: {message}")


@dataclass(frozen=True)
class Book:
    """Un libro ya separado."""

    id: int
    header: str
    body: str


def split(book_id: int, raw: str) -> Book:
    """header es todo lo anterior al START y body lo que hay entre el final de
    la línea START y el END. El footer se descarta."""
    text = raw.replace("\r\n", "\n")

    start = START.search(text)
    end = END.search(text)
    if start is None or end is None or end.start() < start.end():
        raise BookUnavailable(f"marcadores no encontrados en el libro {book_id}")

    return Book(
        id=book_id,
        header=trim_space(text[: start.start()]),
        body=trim_space(text[start.end() : end.start()]),
    )


def cache_path(directory: Path, book_id: int) -> Path:
    return directory / f"{book_id}.txt"


class CacheSource:
    """Lee los .txt crudos de cache/ sin tocar la red (modo --offline)."""

    def __init__(self, directory: Path):
        self.directory = directory

    def fetch(self, book_id: int) -> str:
        try:
            return decode(cache_path(self.directory, book_id).read_bytes())
        except FileNotFoundError:
            raise OSError(f"el libro {book_id} no está en {self.directory}") from None


class GutenbergSource:
    """Descarga de la red. Espera ``delay`` segundos entre peticiones para no
    saturar el servidor y deja una copia cruda en cache_dir."""

    def __init__(self, cache_dir: Path, delay: float, url_template: str = URL_TEMPLATE):
        self.cache_dir = cache_dir
        self.delay = delay
        self.url_template = url_template
        self._last_request: float | None = None

    def fetch(self, book_id: int) -> str:
        if self._last_request is not None:
            time.sleep(max(0.0, self._last_request + self.delay - time.monotonic()))
        try:
            content = self._download(book_id)
        finally:
            self._last_request = time.monotonic()

        self.cache_dir.mkdir(parents=True, exist_ok=True)
        atomic_write(cache_path(self.cache_dir, book_id), content)
        return decode(content)

    def _download(self, book_id: int) -> bytes:
        # Gutenberg sirve algunos libros solo comprimidos (negociación de
        # contenido de Apache): si no se acepta gzip responde 406. Se pide gzip
        # y se descomprime, como hace el cliente HTTP de Go.
        request = urllib.request.Request(self.url_template.format(id=book_id), headers={"Accept-Encoding": "gzip"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                content = response.read()
                if response.headers.get("Content-Encoding", "").lower() == "gzip":
                    content = gzip.decompress(content)
                return content
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise BookUnavailable(f"el libro {book_id} no existe") from None
            raise OSError(f"HTTP {e.code} al descargar el libro {book_id}") from None
