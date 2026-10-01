package index

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

const (
	mongoDatabase = "search_engine"
	mongoTimeout  = 5 * time.Second
)

// mongoIndex: colección inverted_index_<lang> de la base search_engine, con
// documentos {"term": "adventure", "postings": [5, 12, 42]} y un índice único
// en term. Los postings se guardan como conjunto y se ordenan al leer.
type mongoIndex struct {
	client *mongo.Client
	coll   *mongo.Collection
}

type mongoEntry struct {
	Term     string `bson:"term"`
	Postings []int  `bson:"postings"`
}

func newMongoIndex(uri, lang string) (*mongoIndex, error) {
	ctx := context.Background()
	client, err := mongo.Connect(ctx, options.Client().ApplyURI(uri).SetServerSelectionTimeout(mongoTimeout))
	if err != nil {
		return nil, err
	}
	if err := client.Ping(ctx, nil); err != nil {
		client.Disconnect(ctx)
		return nil, fmt.Errorf("no se puede conectar a MongoDB en %s (¿está levantado?): %w", uri, err)
	}
	m := &mongoIndex{
		client: client,
		coll:   client.Database(mongoDatabase).Collection("inverted_index_" + lang),
	}
	if err := m.ensureTermIndex(); err != nil {
		client.Disconnect(ctx)
		return nil, err
	}
	return m, nil
}

func (m *mongoIndex) ensureTermIndex() error {
	_, err := m.coll.Indexes().CreateOne(context.Background(), mongo.IndexModel{
		Keys:    bson.D{{Key: "term", Value: 1}},
		Options: options.Index().SetUnique(true),
	})
	return err
}

func (m *mongoIndex) Name() string { return "mongo" }

func (m *mongoIndex) Add(postings map[string][]int) error {
	if len(postings) == 0 {
		return nil
	}
	updates := make([]mongo.WriteModel, 0, len(postings))
	for term, ids := range postings {
		updates = append(updates, mongo.NewUpdateOneModel().
			SetFilter(bson.D{{Key: "term", Value: term}}).
			SetUpdate(bson.D{{Key: "$addToSet", Value: bson.D{
				{Key: "postings", Value: bson.D{{Key: "$each", Value: ids}}},
			}}}).
			SetUpsert(true))
	}
	_, err := m.coll.BulkWrite(context.Background(), updates, options.BulkWrite().SetOrdered(false))
	return err
}

func (m *mongoIndex) Lookup(term string) ([]int, error) {
	var entry mongoEntry
	err := m.coll.FindOne(context.Background(), bson.D{{Key: "term", Value: term}}).Decode(&entry)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	slices.Sort(entry.Postings)
	return entry.Postings, nil
}

func (m *mongoIndex) Each(fn func(term string, ids []int) error) error {
	ctx := context.Background()
	cursor, err := m.coll.Find(ctx, bson.D{}, options.Find().SetSort(bson.D{{Key: "term", Value: 1}}))
	if err != nil {
		return err
	}
	defer cursor.Close(ctx)

	for cursor.Next(ctx) {
		var entry mongoEntry
		if err := cursor.Decode(&entry); err != nil {
			return err
		}
		slices.Sort(entry.Postings)
		if err := fn(entry.Term, entry.Postings); err != nil {
			return err
		}
	}
	return cursor.Err()
}

func (m *mongoIndex) Empty() (bool, error) {
	count, err := m.coll.EstimatedDocumentCount(context.Background())
	return count == 0, err
}

func (m *mongoIndex) Reset() error {
	if err := m.coll.Drop(context.Background()); err != nil {
		return err
	}
	return m.ensureTermIndex()
}

func (m *mongoIndex) Close() error {
	return m.client.Disconnect(context.Background())
}
