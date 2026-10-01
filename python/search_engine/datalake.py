"""Datalake: header y body de cada libro en disco, con tres organizaciones de
carpetas distintas (SPEC §5)."""

import os
from abc import ABC, abstractmethod
from datetime import datetime
from pathlib import Path
from typing import Callable, NamedTuple

from .fileutil import append_line, atomic_write, encode, load_lines

LOCATIONS_FILE = "_locations.tsv"
RANGE_SIZE = 1000


class Location(NamedTuple):
    """Rutas de las dos mitades de un libro."""

    header: Path
    body: Path


def _parse_id(text: str) -> int | None:
    try:
        return int(text)
    except ValueError:
        return None


class Store(ABC):
    """Contrato común a las tres variantes del datalake."""

    name: str

    def __init__(self, root: Path):
        self.root = root

    @abstractmethod
    def save(self, book_id: int, header: str, body: str) -> Location:
        """Escribe el libro; repetirlo con el mismo ID lo sobrescribe en su
        sitio, nunca lo duplica."""

    @abstractmethod
    def locate(self, book_id: int) -> Location | None:
        """None si falta alguna de las dos mitades."""

    @abstractmethod
    def list(self) -> list[int]:
        """IDs de los libros completos, en orden ascendente."""


def new_store(kind: str, output_dir: Path) -> Store:
    """Crea la variante pedida dentro de output_dir (output/<lang>)."""
    root = output_dir / f"datalake_{kind}"
    if kind == "time":
        return TimeStore(root)
    if kind == "book":
        return BookStore(root)
    if kind == "range":
        return RangeStore(root)
    raise ValueError(f'datalake desconocido "{kind}" (time, book o range)')


def _flat_location(directory: Path, book_id: int) -> Location:
    """Disposición "<id>.header.txt / <id>.body.txt" que comparten time y range."""
    return Location(directory / f"{book_id}.header.txt", directory / f"{book_id}.body.txt")


def _write(location: Location, header: str, body: str) -> None:
    """Deja el body para el final: su existencia marca el libro como completo."""
    location.body.parent.mkdir(parents=True, exist_ok=True)
    atomic_write(location.header, encode(header))
    atomic_write(location.body, encode(body))


def _complete(location: Location) -> bool:
    return location.header.is_file() and location.body.is_file()


class BookStore(Store):
    """datalake_book/<id>/header.txt y body.txt"""

    name = "book"

    def _location(self, book_id: int) -> Location:
        directory = self.root / str(book_id)
        return Location(directory / "header.txt", directory / "body.txt")

    def save(self, book_id, header, body):
        location = self._location(book_id)
        _write(location, header, body)
        return location

    def locate(self, book_id):
        location = self._location(book_id)
        return location if _complete(location) else None

    def list(self):
        try:
            names = os.listdir(self.root)
        except FileNotFoundError:
            return []
        ids = (_parse_id(name) for name in names)
        return sorted(i for i in ids if i is not None and self.locate(i))


class RangeStore(Store):
    """datalake_range/<inicio>-<fin>/<id>.header.txt y <id>.body.txt, con
    rangos de 1000 (0-999, 1000-1999, ...)."""

    name = "range"

    def _location(self, book_id: int) -> Location:
        start = book_id // RANGE_SIZE * RANGE_SIZE
        return _flat_location(self.root / f"{start}-{start + RANGE_SIZE - 1}", book_id)

    def save(self, book_id, header, body):
        location = self._location(book_id)
        _write(location, header, body)
        return location

    def locate(self, book_id):
        location = self._location(book_id)
        return location if _complete(location) else None

    def list(self):
        try:
            folders = [entry for entry in os.scandir(self.root) if entry.is_dir()]
        except FileNotFoundError:
            return []
        ids = []
        for folder in folders:
            for name in os.listdir(folder.path):
                if name.endswith(".body.txt"):
                    book_id = _parse_id(name.removesuffix(".body.txt"))
                    if book_id is not None and self.locate(book_id):
                        ids.append(book_id)
        return sorted(ids)


class TimeStore(Store):
    """datalake_time/YYYYMMDD/HH/<id>.header.txt y <id>.body.txt

    La ruta depende de cuándo se guardó el libro, así que se mantiene el índice
    auxiliar _locations.tsv (id\\tYYYYMMDD/HH) para poder localizarlo.
    """

    name = "time"

    def __init__(self, root: Path, now: Callable[[], datetime] = datetime.now):
        super().__init__(root)
        self.now = now
        self.locations: dict[int, str] = {}
        for line in load_lines(root / LOCATIONS_FILE):
            id_text, sep, folder = line.partition("\t")
            book_id = _parse_id(id_text)
            if sep and book_id is not None:
                self.locations[book_id] = folder

    def _location(self, book_id: int, folder: str) -> Location:
        return _flat_location(self.root.joinpath(*folder.split("/")), book_id)

    def save(self, book_id, header, body):
        # La carpeta se registra antes de escribir los ficheros: si el proceso
        # se corta a mitad, al reanudar el libro vuelve a la misma carpeta
        # aunque haya cambiado la hora, y no queda duplicado en dos sitios.
        folder = self.locations.get(book_id)
        if folder is None:
            folder = self.now().strftime("%Y%m%d/%H")
            self.root.mkdir(parents=True, exist_ok=True)
            append_line(self.root / LOCATIONS_FILE, f"{book_id}\t{folder}")
            self.locations[book_id] = folder
        location = self._location(book_id, folder)
        _write(location, header, body)
        return location

    def locate(self, book_id):
        folder = self.locations.get(book_id)
        if folder is None:
            return None
        location = self._location(book_id, folder)
        return location if _complete(location) else None

    def list(self):
        return sorted(i for i in self.locations if self.locate(i))
