"""Convierte un texto en los términos que se indexan (SPEC §7).

Las reglas tienen que dar el mismo resultado en Python, Java y Go, así que se
trabaja sobre bytes: ``bytes.lower()`` solo cambia A-Z, sin reglas Unicode.
"""

import re
from pathlib import Path

from .fileutil import decode, encode
from .text import trim_space

MIN_LENGTH = 2
TOKEN = re.compile(rb"[a-z]+")


def load_stopwords(path: Path) -> frozenset[str]:
    """Lee un fichero con una palabra por línea."""
    lines = (trim_space(line) for line in decode(path.read_bytes()).split("\n"))
    return frozenset(line for line in lines if line)


def terms(text: bytes | str, stopwords: frozenset[str] = frozenset()) -> list[str]:
    """Términos distintos de un texto, en orden de aparición:

    1. minúsculas solo para A-Z;
    2. un token es una secuencia [a-z]+; todo lo demás separa;
    3. se descartan los tokens de menos de 2 letras;
    4. se descartan las stopwords;
    5. cada término aparece una sola vez.
    """
    if isinstance(text, str):
        text = encode(text)
    unique = dict.fromkeys(TOKEN.findall(text.lower()))
    words = (token.decode("ascii") for token in unique)
    return [word for word in words if len(word) >= MIN_LENGTH and word not in stopwords]
