"""Operaciones de fichero que comparten el datalake, los datamarts y el control."""

import os
from pathlib import Path

from .text import trim_space

# Los libros se leen y escriben en UTF-8. Con surrogateescape, un byte que no sea
# UTF-8 válido se conserva tal cual, igual que en Go, en lugar de abortar.
ENCODING = "utf-8"
ERRORS = "surrogateescape"


def decode(data: bytes) -> str:
    return data.decode(ENCODING, ERRORS)


def encode(text: str) -> bytes:
    return text.encode(ENCODING, ERRORS)


def atomic_write(path: Path, data: bytes) -> None:
    """Escribe en "<path>.tmp" y renombra al final: si el proceso se corta a
    mitad, nunca queda un fichero final a medias."""
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def append_line(path: Path, line: str) -> None:
    """Añade una línea al final de un fichero de registro, creándolo si no existe."""
    with open(path, "ab") as f:
        f.write(encode(line + "\n"))


def load_lines(path: Path) -> list[str]:
    """Devuelve las líneas no vacías de un fichero de registro; lista vacía si
    no existe.

    Una última línea sin "\\n" es un append interrumpido: se descarta y se
    recorta el fichero, para que el siguiente append no se pegue a ella.
    """
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        return []

    end = data.rfind(b"\n") + 1
    if end < len(data):
        with open(path, "r+b") as f:
            f.truncate(end)
        data = data[:end]

    lines = (trim_space(line) for line in decode(data).split("\n"))
    return [line for line in lines if line]


def remove_temp(root: Path) -> int:
    """Borra los ".tmp" que haya dejado una escritura interrumpida y devuelve
    cuántos ha borrado."""
    removed = 0
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if name.endswith(".tmp"):
                os.remove(os.path.join(dirpath, name))
                removed += 1
    return removed


def tree_stats(root: Path) -> tuple[int, int, int]:
    """Cuenta los ficheros y carpetas que hay bajo root (sin contar root) y suma
    el tamaño de los ficheros. Si root no existe, todo es cero."""
    files = dirs = size = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirs += len(dirnames)
        files += len(filenames)
        size += sum(os.path.getsize(os.path.join(dirpath, name)) for name in filenames)
    return files, dirs, size
