"""Descarga de Gutenberg (§4) contra un servidor local que se comporta como el
de Gutenberg: algunos libros solo existen comprimidos y, si el cliente no
acepta gzip, Apache responde 406."""

import gzip
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from search_engine.ingestion import BookUnavailable, GutenbergSource, split

BOOK = (b"Title: Libro\r\n*** START OF THE PROJECT GUTENBERG EBOOK ***\r\n"
        b"cuerpo\r\n*** END OF THE PROJECT GUTENBERG EBOOK ***\r\n")


class Gutenberg(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/1.txt":  # solo existe la variante comprimida
            if "gzip" not in self.headers.get("Accept-Encoding", ""):
                self.send_response(406)
                self.end_headers()
                return
            body = gzip.compress(BOOK)
            self.send_response(200)
            self.send_header("Content-Encoding", "gzip")
        elif self.path == "/2.txt":
            body = BOOK
            self.send_response(200)
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture
def server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Gutenberg)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}/{{id}}.txt"
    httpd.shutdown()


def test_descarga_comprimida_y_sin_comprimir(server, tmp_path):
    source = GutenbergSource(tmp_path / "cache", 0, url_template=server)
    for book_id in (1, 2):
        assert split(book_id, source.fetch(book_id)).body == "cuerpo"
        # La caché guarda el texto crudo, ya descomprimido.
        assert (tmp_path / "cache" / f"{book_id}.txt").read_bytes() == BOOK


def test_404_descarta_el_libro(server, tmp_path):
    with pytest.raises(BookUnavailable):
        GutenbergSource(tmp_path / "cache", 0, url_template=server).fetch(3)
