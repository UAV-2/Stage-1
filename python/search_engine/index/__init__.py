"""Índice invertido (término -> libros) sobre tres estructuras distintas con el
mismo contenido lógico (SPEC §8)."""

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Iterator

from ..fileutil import atomic_write
from ..tokenizer import terms


class Index(ABC):
    """Contrato común a las tres variantes. Los postings son siempre IDs de
    libro únicos y en orden ascendente."""

    name: str

    @abstractmethod
    def add(self, postings: dict[str, list[int]]) -> None:
        """Fusiona un lote de postings con lo que ya hay en el índice. Repetir
        el mismo lote no cambia el resultado."""

    @abstractmethod
    def lookup(self, term: str) -> list[int]:
        """Libros de un término; vacío si no está."""

    @abstractmethod
    def each(self) -> Iterator[tuple[str, list[int]]]:
        """Recorre el índice entero en orden alfabético de término."""

    @abstractmethod
    def empty(self) -> bool: ...

    @abstractmethod
    def disk_usage(self) -> int:
        """Bytes que ocupa el índice."""

    @abstractmethod
    def reset(self) -> None:
        """Borra el índice para reconstruirlo desde cero."""

    def close(self) -> None:
        pass


def new_index(kind: str, datamarts: Path, mongo_uri: str, lang: str) -> Index:
    if kind == "json":
        from .json_index import JsonIndex

        return JsonIndex(datamarts / "inverted_index.json")
    if kind == "folders":
        from .folders import FolderIndex

        return FolderIndex(datamarts / "inverted_index")
    if kind == "mongo":
        from .mongo import MongoIndex

        return MongoIndex(mongo_uri, lang)
    raise ValueError(f'índice desconocido "{kind}" (json, folders o mongo)')


def merge(existing: list[int], added: list[int]) -> list[int]:
    """Une dos listas de IDs y deja el resultado ordenado y sin repetidos."""
    return sorted(set(existing).union(added))


def search(index: Index, query: str, stopwords: frozenset[str]) -> list[int]:
    """Normaliza la consulta con las mismas reglas que la indexación y devuelve
    los libros que contienen todos sus términos (AND)."""
    words = terms(query, stopwords)
    if not words:
        return []
    result = set(index.lookup(words[0]))
    for word in words[1:]:
        if not result:
            break
        result.intersection_update(index.lookup(word))
    return sorted(result)


def dump_tsv(index: Index, path: Path) -> None:
    """Dump canónico: término\\tid1,id2,id3, ordenado por término."""
    lines = (f"{term}\t{','.join(map(str, ids))}\n" for term, ids in index.each())
    atomic_write(path, "".join(lines).encode("ascii"))
