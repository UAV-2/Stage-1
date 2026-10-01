package es.ulpgc.bigdata.metadata;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Datamart de metadatos (datamarts/metadata.db, SQLite). */
public class MetadataDatabase implements AutoCloseable {

    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS books (
                book_id   INTEGER PRIMARY KEY,
                title     TEXT,
                author    TEXT,
                language  TEXT,
                body_path TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)",
            "CREATE INDEX IF NOT EXISTS idx_books_title  ON books(title)",
    };

    private final Connection connection;

    public MetadataDatabase(Path path) throws IOException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement statement = connection.createStatement()) {
                for (String sql : SCHEMA) {
                    statement.executeUpdate(sql);
                }
            }
        } catch (SQLException e) {
            throw new IOException("no se puede abrir " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Guarda los libros en una sola transacción. Repetir un libro lo reemplaza, así que
     * reindexar tras un corte no duplica filas.
     */
    public void insert(List<BookMetadata> books) throws IOException {
        String sql = "INSERT OR REPLACE INTO books (book_id, title, author, language, body_path) VALUES (?, ?, ?, ?, ?)";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (BookMetadata book : books) {
                    statement.setInt(1, book.id());
                    setNullable(statement, 2, book.title());
                    setNullable(statement, 3, book.author());
                    setNullable(statement, 4, book.language());
                    statement.setString(5, book.bodyPath());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isEmpty()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /** Todos los libros ordenados por ID. */
    public List<BookMetadata> all() throws IOException {
        return query("""
                SELECT book_id, COALESCE(title, ''), COALESCE(author, ''), COALESCE(language, ''), body_path
                FROM books ORDER BY book_id""");
    }

    private List<BookMetadata> query(String sql, Object... params) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            List<BookMetadata> books = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    books.add(new BookMetadata(rows.getInt(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5)));
                }
            }
            return books;
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public boolean isEmpty() throws IOException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM books")) {
            return rows.next() && rows.getInt(1) == 0;
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Vacía la tabla para reconstruirla desde cero. */
    public void reset() throws IOException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM books");
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Escribe el dump canónico: book_id\ttitle\tauthor\tlanguage, ordenado por ID y con los
     * NULL como cadena vacía.
     */
    public void dumpTsv(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (BookMetadata book : all()) {
            sb.append(book.id()).append('\t')
                    .append(book.title()).append('\t')
                    .append(book.author()).append('\t')
                    .append(book.language()).append('\n');
        }
        FileUtils.atomicWrite(path, sb.toString());
    }

    @Override
    public void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
