"""Extrae título, autor e idioma de la cabecera de cada libro y los guarda en
SQLite (SPEC §6)."""

import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from .fileutil import atomic_write, encode
from .text import to_lower, trim_space

# [\t\n\f\r ] en lugar de \s: el \s de Python también acepta \v, \x1c-\x1f y los
# espacios Unicode, y el de Go no. Así los tres lenguajes cortan igual.
TITLE = re.compile(r"^Title:[\t\n\f\r ]*(.+)$", re.IGNORECASE)
AUTHOR = re.compile(r"^Author:[\t\n\f\r ]*(.+)$", re.IGNORECASE)
LANGUAGE = re.compile(r"^Language:[\t\n\f\r ]*(.+)$", re.IGNORECASE)

LANGUAGE_CODES = {"English": "en", "Spanish": "es", "French": "fr", "German": "de"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS books (
    book_id   INTEGER PRIMARY KEY,
    title     TEXT,
    author    TEXT,
    language  TEXT,
    body_path TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_books_author ON books(author);
CREATE INDEX IF NOT EXISTS idx_books_title  ON books(title);
"""


@dataclass(frozen=True)
class BookMetadata:
    """Una fila de la tabla books. None = el dato no está en la cabecera (NULL)."""

    id: int
    title: str | None
    author: str | None
    language: str | None
    body_path: str = ""


@dataclass(frozen=True)
class Filter:
    """Selección por coincidencia exacta. Un campo vacío no filtra; si hay
    varios, se tienen que cumplir todos."""

    title: str | None = None
    author: str | None = None
    language: str | None = None

    def empty(self) -> bool:
        return not (self.title or self.author or self.language)


def _first_match(pattern: re.Pattern, lines: list[str]) -> str | None:
    """Cuenta la primera línea que casa: si su valor queda vacío, el campo es
    NULL y no se sigue buscando."""
    for line in lines:
        match = pattern.search(line)
        if match:
            return trim_space(match.group(1)) or None
    return None


def _normalize_language(language: str | None) -> str | None:
    if language is None:
        return None
    return LANGUAGE_CODES.get(language, to_lower(language))


def extract(book_id: int, header: str) -> BookMetadata:
    """Busca cada campo línea a línea y se queda con la primera coincidencia. Si
    el título continúa en la línea siguiente, la continuación se ignora."""
    lines = header.split("\n")
    return BookMetadata(
        id=book_id,
        title=_first_match(TITLE, lines),
        author=_first_match(AUTHOR, lines),
        language=_normalize_language(_first_match(LANGUAGE, lines)),
    )


class MetadataDB:
    """Datamart de metadatos (datamarts/metadata.db)."""

    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path)
        self.conn.executescript(SCHEMA)

    def close(self) -> None:
        self.conn.close()

    def insert(self, books: list[BookMetadata]) -> None:
        """Guarda los libros en una sola transacción. Repetir un libro lo
        reemplaza, así que reindexar tras un corte no duplica filas."""
        with self.conn:
            self.conn.executemany(
                "INSERT OR REPLACE INTO books (book_id, title, author, language, body_path) VALUES (?, ?, ?, ?, ?)",
                [(b.id, b.title, b.author, b.language, b.body_path) for b in books],
            )

    def _query(self, where: str = "", params: tuple = ()) -> list[BookMetadata]:
        rows = self.conn.execute(
            f"SELECT book_id, title, author, language, body_path FROM books{where} ORDER BY book_id", params
        )
        return [BookMetadata(*row) for row in rows]

    def all(self) -> list[BookMetadata]:
        """Todos los libros ordenados por ID."""
        return self._query()

    def by_id(self, book_id: int) -> BookMetadata | None:
        """Un libro; con él se obtiene la ruta de su body."""
        books = self._query(" WHERE book_id = ?", (book_id,))
        return books[0] if books else None

    def find(self, filter: Filter) -> list[BookMetadata]:
        """Libros que cumplen el filtro, ordenados por ID."""
        fields = [("title", filter.title), ("author", filter.author), ("language", filter.language)]
        conditions = [(f"{column} = ?", value) for column, value in fields if value]
        if not conditions:
            return self.all()
        where = " WHERE " + " AND ".join(condition for condition, _ in conditions)
        return self._query(where, tuple(value for _, value in conditions))

    def empty(self) -> bool:
        return self.conn.execute("SELECT COUNT(*) FROM books").fetchone()[0] == 0

    def reset(self) -> None:
        """Vacía la tabla para reconstruirla desde cero."""
        with self.conn:
            self.conn.execute("DELETE FROM books")

    def dump_tsv(self, path: Path) -> None:
        """Dump canónico: book_id\\ttitle\\tauthor\\tlanguage, ordenado por ID y con
        los NULL como cadena vacía."""
        lines = (f"{b.id}\t{b.title or ''}\t{b.author or ''}\t{b.language or ''}\n" for b in self.all())
        atomic_write(path, encode("".join(lines)))
