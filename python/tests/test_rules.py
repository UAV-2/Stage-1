"""Reglas comunes a los tres lenguajes: separación (§4), metadatos (§6) y
tokenización (§7). Los casos son los mismos que en los tests de Go y Java."""

import pytest

from search_engine.ingestion import BookUnavailable, split
from search_engine.metadata import BookMetadata, extract
from search_engine.text import to_lower, trim_space
from search_engine.tokenizer import load_stopwords, terms


@pytest.mark.parametrize(
    "raw, header, body",
    [
        pytest.param(
            "Title: A\n\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\n\nTexto\n\n*** END OF THE PROJECT GUTENBERG EBOOK A ***\nLicencia",
            "Title: A", "Texto", id="marcadores actuales",
        ),
        pytest.param(
            "Title: B\n***START OF THIS PROJECT GUTENBERG EBOOK B***\nTexto\n***END OF THIS PROJECT GUTENBERG EBOOK B***",
            "Title: B", "Texto", id="libro antiguo con THIS y sin espacio",
        ),
        pytest.param(
            "Title: C\n*** start of the project gutenberg ebook c ***\nTexto\n*** end of the project gutenberg ebook c ***",
            "Title: C", "Texto", id="minúsculas",
        ),
        pytest.param(
            "Title: D\r\n*** START OF THE PROJECT GUTENBERG EBOOK D ***\r\nUno\r\nDos\r\n*** END OF THE PROJECT GUTENBERG EBOOK D ***\r\n",
            "Title: D", "Uno\nDos", id="saltos de línea de Windows",
        ),
        pytest.param(
            # U+00A0 se recorta (Go lo considera espacio); U+FEFF (BOM) no.
            "﻿Title: E\xa0\n*** START OF THE PROJECT GUTENBERG EBOOK E ***\n\xa0Texto\xa0\n*** END OF THE PROJECT GUTENBERG EBOOK E ***",
            "﻿Title: E", "Texto", id="espacios Unicode como en Go",
        ),
    ],
)
def test_split(raw, header, body):
    book = split(1, raw)
    assert (book.header, book.body) == (header, body)


@pytest.mark.parametrize(
    "raw",
    [
        "texto sin marcadores",
        "*** START OF THE PROJECT GUTENBERG EBOOK A ***\nsin final",
        "sin principio\n*** END OF THE PROJECT GUTENBERG EBOOK A ***",
        # El END queda antes del START: se trata como si faltaran.
        "*** END OF THE PROJECT GUTENBERG EBOOK A ***\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\n",
    ],
)
def test_split_sin_marcadores(raw):
    with pytest.raises(BookUnavailable):
        split(1, raw)


def test_trim_space_como_go():
    # str.strip() quitaría también \x1c-\x1f; strings.TrimSpace de Go no.
    assert trim_space("\x1c\t a \xa0　") == "\x1c\t a"
    assert to_lower("ÉCOLE İ ΣΑΣ") == "école i σασ"


@pytest.mark.parametrize(
    "header, title, author, language",
    [
        (
            "The Project Gutenberg eBook of Pride and Prejudice\n\nTitle: Pride and Prejudice\n\nAuthor: Jane Austen\n\nRelease date: June 1, 1998\n\nLanguage: English",
            "Pride and Prejudice", "Jane Austen", "en",
        ),
        ("TITLE:   Moby Dick  \nauthor:Herman Melville\nLANGUAGE:\tFrench", "Moby Dick", "Herman Melville", "fr"),
        # Primera coincidencia; la continuación del título se ignora.
        ("Title: The Life and Adventures\n       of Robinson Crusoe\nTitle: Otro\nLanguage: Spanish",
         "The Life and Adventures", None, "es"),
        ("Title: Kalevala\nLanguage: Finnish", "Kalevala", None, "finnish"),
        # Coincidencia exacta: "english" no es "English".
        ("Language: english", None, None, "english"),
        # La etiqueta tiene que estar al principio de la línea.
        ("Original Title: No\n  Author: Tampoco\nLanguage: German", None, None, "de"),
        # Campo vacío -> NULL, y no se sigue buscando.
        ("Title:\nAuthor:   \nAuthor: Segundo\nsin idioma", None, None, None),
        # Solo se separa por \n: \r y \x0b siguen en la línea y strip() los quita.
        ("Title: Uno\r\nAuthor: Dos\x0bTres", "Uno", "Dos\x0bTres", None),
    ],
)
def test_extract(header, title, author, language):
    assert extract(7, header) == BookMetadata(7, title, author, language)


STOP = frozenset({"the", "and"})


@pytest.mark.parametrize(
    "text, expected",
    [
        ("don't", ["don"]),
        ("well-known", ["well", "known"]),
        ("café", ["caf"]),
        ("The ISLAND and the Island", ["island"]),
        ("a I x", []),
        ("chapter 12: snake_case", ["chapter", "snake", "case"]),
        # Las mayúsculas fuera de A-Z no se convierten: separan.
        ("ÉCOLE Ángel", ["cole", "ngel"]),
        ("sea, ship; sea. SHIP!", ["sea", "ship"]),
        ("", []),
    ],
)
def test_terms(text, expected):
    assert terms(text, STOP) == expected
    assert terms(text.encode("utf-8"), STOP) == expected


def test_load_stopwords(tmp_path):
    path = tmp_path / "stopwords.txt"
    path.write_bytes(b"the\r\nand\n\nof")
    stop = load_stopwords(path)
    assert stop == {"the", "and", "of"}
    assert terms("the tale of two cities", stop) == ["tale", "two", "cities"]
