"""Utilidades de texto que se comportan igual en Python, Java y Go (SPEC §4 y §6).

Las funciones de Python equivalentes difieren en casos límite: ``str.strip()``
también quita ``\\x1c``-``\\x1f`` y ``str.lower()`` aplica reglas de contexto
(sigma final) o devuelve dos caracteres (``İ``).
"""

# Los espacios de unicode.IsSpace de Go, que es la definición de strip() del SPEC.
SPACES = (
    "\t\n\v\f\r \x85\xa0 "
    "           "
    "    　"
)


def trim_space(text: str) -> str:
    """Equivalente a strings.TrimSpace de Go."""
    return text.strip(SPACES)


def to_lower(text: str) -> str:
    """Minúsculas carácter a carácter, como strings.ToLower de Go."""
    return "".join(char.lower()[0] for char in text)


def format_ids(ids) -> str:
    """Formato de una lista de IDs en la salida por consola: [1 2 3]."""
    return "[" + " ".join(map(str, ids)) + "]"
