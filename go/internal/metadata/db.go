package metadata

import (
	"database/sql"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	_ "github.com/mattn/go-sqlite3"

	"stage1_go/internal/fileutil"
)

const schema = `
CREATE TABLE IF NOT EXISTS books (
    book_id   INTEGER PRIMARY KEY,
    title     TEXT,
    author    TEXT,
    language  TEXT,
    body_path TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_books_author ON books(author);
CREATE INDEX IF NOT EXISTS idx_books_title  ON books(title);
`

// DB es el datamart de metadatos (datamarts/metadata.db).
type DB struct {
	db *sql.DB
}

func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite3", path)
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	return &DB{db: db}, nil
}

func (d *DB) Close() error { return d.db.Close() }

// Insert guarda los libros en una sola transacción. Repetir un libro lo
// reemplaza, así que reindexar tras un corte no duplica filas.
func (d *DB) Insert(books []Book) error {
	tx, err := d.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	stmt, err := tx.Prepare(`INSERT OR REPLACE INTO books (book_id, title, author, language, body_path) VALUES (?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, book := range books {
		_, err := stmt.Exec(book.ID, nullable(book.Title), nullable(book.Author), nullable(book.Language), book.BodyPath)
		if err != nil {
			return err
		}
	}
	return tx.Commit()
}

func nullable(value string) any {
	if value == "" {
		return nil
	}
	return value
}

const selectBooks = `SELECT book_id, COALESCE(title, ''), COALESCE(author, ''), COALESCE(language, ''), body_path FROM books`

func (d *DB) query(where string, args ...any) ([]Book, error) {
	rows, err := d.db.Query(selectBooks+where+` ORDER BY book_id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var books []Book
	for rows.Next() {
		var book Book
		if err := rows.Scan(&book.ID, &book.Title, &book.Author, &book.Language, &book.BodyPath); err != nil {
			return nil, err
		}
		books = append(books, book)
	}
	return books, rows.Err()
}

// All devuelve todos los libros ordenados por ID.
func (d *DB) All() ([]Book, error) {
	return d.query("")
}

// ByID devuelve un libro; con él se obtiene la ruta de su body.
func (d *DB) ByID(id int) (Book, bool, error) {
	books, err := d.query(` WHERE book_id = ?`, id)
	if err != nil || len(books) == 0 {
		return Book{}, false, err
	}
	return books[0], true, nil
}

// Filter selecciona libros por coincidencia exacta. Un campo vacío no
// filtra; si hay varios, se tienen que cumplir todos.
type Filter struct {
	Title    string
	Author   string
	Language string
}

func (f Filter) Empty() bool { return f == Filter{} }

// Find devuelve los libros que cumplen el filtro, ordenados por ID.
func (d *DB) Find(filter Filter) ([]Book, error) {
	var conditions []string
	var args []any
	for _, field := range []struct{ column, value string }{
		{"title", filter.Title},
		{"author", filter.Author},
		{"language", filter.Language},
	} {
		if field.value != "" {
			conditions = append(conditions, field.column+" = ?")
			args = append(args, field.value)
		}
	}
	if len(conditions) == 0 {
		return d.All()
	}
	return d.query(" WHERE "+strings.Join(conditions, " AND "), args...)
}

func (d *DB) Empty() (bool, error) {
	var count int
	err := d.db.QueryRow(`SELECT COUNT(*) FROM books`).Scan(&count)
	return count == 0, err
}

// Reset vacía la tabla para reconstruirla desde cero.
func (d *DB) Reset() error {
	_, err := d.db.Exec(`DELETE FROM books`)
	return err
}

// DumpTSV escribe el dump canónico: book_id\ttitle\tauthor\tlanguage,
// ordenado por ID y con los NULL como cadena vacía.
func (d *DB) DumpTSV(path string) error {
	books, err := d.All()
	if err != nil {
		return err
	}
	var sb strings.Builder
	for _, book := range books {
		sb.WriteString(strconv.Itoa(book.ID))
		sb.WriteByte('\t')
		sb.WriteString(book.Title)
		sb.WriteByte('\t')
		sb.WriteString(book.Author)
		sb.WriteByte('\t')
		sb.WriteString(book.Language)
		sb.WriteByte('\n')
	}
	return fileutil.AtomicWrite(path, []byte(sb.String()))
}
